package com.shopstream.inventory.web;

import com.shopstream.inventory.cache.ProductCacheService;
import com.shopstream.inventory.dto.CreateProductRequest;
import com.shopstream.inventory.dto.ProductResponse;
import com.shopstream.inventory.dto.ReservationResponse;
import com.shopstream.inventory.exception.DuplicateSkuException;
import com.shopstream.inventory.exception.InsufficientStockException;
import com.shopstream.inventory.exception.ProductNotFoundException;
import com.shopstream.inventory.exception.ReservationNotFoundException;
import com.shopstream.inventory.product.Product;
import com.shopstream.inventory.product.ProductRepository;
import com.shopstream.inventory.reservation.ReservationStatus;
import com.shopstream.inventory.reservation.StockReservation;
import com.shopstream.inventory.reservation.StockReservationRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final StockReservationRepository reservationRepository;
    private final ProductCacheService cache;

    public ProductService(ProductRepository productRepository, StockReservationRepository reservationRepository,
            ProductCacheService cache) {
        this.productRepository = productRepository;
        this.reservationRepository = reservationRepository;
        this.cache = cache;
    }

    @Transactional
    public ProductResponse createProduct(CreateProductRequest request) {
        if (productRepository.existsBySku(request.sku())) {
            throw new DuplicateSkuException(request.sku());
        }
        Product product = Product.create(
                request.sku(), request.name(), request.description(), request.price(), request.initialStock());
        productRepository.save(product);
        return toResponse(product);
    }

    /**
     * Cache-aside read: check Redis first; on a miss, load from MySQL and
     * populate the cache for next time. Redis never talks to MySQL itself --
     * the application always sits in the middle of that decision.
     */
    @Transactional(readOnly = true)
    public ProductResponse getProduct(UUID productId) {
        ProductResponse cached = cache.get(productId);
        if (cached != null) {
            return cached;
        }
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        ProductResponse response = toResponse(product);
        cache.put(productId, response);
        return response;
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> listProducts(Pageable pageable) {
        return productRepository.findAll(pageable).map(this::toResponse);
    }

    /**
     * Reserves {@code quantity} units of a product against a caller-supplied
     * {@code reservationId}.
     *
     * <p><b>Idempotency:</b> if a reservation with this id already exists, we
     * return its existing result instead of reserving again -- this makes it
     * safe for a caller to retry the exact same request (e.g. after a
     * timeout) without double-decrementing stock.
     *
     * <p><b>Concurrency:</b> {@code @Retryable} re-runs this ENTIRE method
     * (including its own {@code @Transactional} boundary) from scratch on an
     * {@link ObjectOptimisticLockingFailureException} -- i.e. on a fresh
     * transaction and a fresh persistence context each attempt. That matters:
     * once an optimistic-lock conflict happens mid-transaction, the current
     * persistence context is not safe to keep using, so retrying by simply
     * catching the exception and looping inside the same method would be
     * incorrect. Retrying the whole method call sidesteps that entirely, and
     * the idempotency check above makes each retry attempt safe to repeat.
     */
    @Transactional
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2))
    public ReservationResponse reserveStock(UUID productId, String reservationId, int quantity) {
        var existing = reservationRepository.findById(reservationId);
        if (existing.isPresent()) {
            return toReservationResponse(existing.get());
        }

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        if (product.getStockQuantity() < quantity) {
            throw new InsufficientStockException(productId, quantity, product.getStockQuantity());
        }
        product.setStockQuantity(product.getStockQuantity() - quantity);
        // Flushing now (rather than waiting for the transaction to commit) surfaces
        // an optimistic-lock conflict here, before we go on to insert the
        // reservation row -- so a losing attempt does less wasted work.
        productRepository.saveAndFlush(product);

        StockReservation reservation = StockReservation.create(reservationId, productId, quantity);
        reservationRepository.save(reservation);

        cache.evict(productId);
        return toReservationResponse(reservation);
    }

    /**
     * Releases a previously reserved quantity back to available stock.
     * Idempotent the same way {@link #reserveStock} is: releasing an already-
     * released reservation is a no-op, not an error.
     */
    @Transactional
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2))
    public void releaseStock(String reservationId) {
        StockReservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            return;
        }

        Product product = productRepository.findById(reservation.getProductId())
                .orElseThrow(() -> new ProductNotFoundException(reservation.getProductId()));
        product.setStockQuantity(product.getStockQuantity() + reservation.getQuantity());
        productRepository.saveAndFlush(product);

        reservation.setStatus(ReservationStatus.RELEASED);
        reservationRepository.save(reservation);

        cache.evict(reservation.getProductId());
    }

    private ProductResponse toResponse(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStockQuantity(),
                product.getCreatedAt());
    }

    private ReservationResponse toReservationResponse(StockReservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getProductId(),
                reservation.getQuantity(),
                reservation.getStatus().name());
    }
}
