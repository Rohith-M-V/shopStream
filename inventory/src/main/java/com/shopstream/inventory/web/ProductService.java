package com.shopstream.inventory.web;

import com.shopstream.common.events.EventEnvelope;
import com.shopstream.common.events.EventTypes;
import com.shopstream.inventory.cache.ProductCacheService;
import com.shopstream.inventory.dto.CreateProductRequest;
import com.shopstream.inventory.dto.ProductResponse;
import com.shopstream.inventory.dto.ReservationResponse;
import com.shopstream.inventory.event.InventoryOutcomePayload;
import com.shopstream.inventory.exception.DuplicateSkuException;
import com.shopstream.inventory.exception.InsufficientStockException;
import com.shopstream.inventory.exception.ProductNotFoundException;
import com.shopstream.inventory.exception.ReservationNotFoundException;
import com.shopstream.inventory.outbox.OutboxEvent;
import com.shopstream.inventory.outbox.OutboxEventRepository;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ProductService {

    private static final String AGGREGATE_TYPE = "Order";

    // Built locally rather than injected, same reasoning as order's OrderService:
    // Boot 4 moved to Jackson 3 (tools.jackson.*), and building the mapper
    // directly avoids depending on which ObjectMapper flavour Boot exposes.
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private final ProductRepository productRepository;
    private final StockReservationRepository reservationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ProductCacheService cache;

    public ProductService(ProductRepository productRepository, StockReservationRepository reservationRepository,
            OutboxEventRepository outboxEventRepository, ProductCacheService cache) {
        this.productRepository = productRepository;
        this.reservationRepository = reservationRepository;
        this.outboxEventRepository = outboxEventRepository;
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
     * {@code reservationId}. By convention (see order's Order entity),
     * {@code reservationId} IS the order id -- so a successful reservation
     * here also writes an InventoryReserved outbox event, correlated back to
     * that same order id, using the exact same transactional-outbox guarantee
     * order's own OrderService relies on: the stock decrement, the
     * reservation row, and the outbox row all commit together or not at all.
     *
     * <p>This method is called from two places: directly via HTTP
     * ({@link com.shopstream.inventory.web.ProductController#reserve}) and
     * from {@link com.shopstream.inventory.event.OrderEventsListener} in
     * response to a real OrderCreated event. Both paths get the same outbox
     * guarantee for free, since it lives in this one shared method rather
     * than being duplicated per caller.
     *
     * <p><b>Idempotency:</b> if a reservation with this id already exists, we
     * return its existing result instead of reserving again -- this makes it
     * safe for a caller to retry the exact same request (e.g. after a
     * timeout, or a redelivered Kafka message) without double-decrementing
     * stock or publishing a duplicate outbox event.
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
            maxAttempts = 10,
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

        var payload = InventoryOutcomePayload.reserved(reservationId, productId, quantity);
        var envelope = EventEnvelope.of(EventTypes.INVENTORY_RESERVED, reservationId, payload);
        outboxEventRepository.save(OutboxEvent.create(
                AGGREGATE_TYPE, reservationId, EventTypes.INVENTORY_RESERVED, serialize(envelope)));

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

    /**
     * Records that a reservation could not be fulfilled -- called by
     * {@link com.shopstream.inventory.event.OrderEventsListener} when
     * {@link #reserveStock} throws a business exception (insufficient stock,
     * unknown product). This writes the InventoryFailed outbox event as its
     * own short transaction; it does NOT retry the reservation itself, because
     * these are business outcomes, not transient failures -- retrying
     * "insufficient stock" can't produce more stock.
     */
    @Transactional
    public void recordReservationFailure(UUID productId, String reservationId, String reason) {
        var payload = InventoryOutcomePayload.failed(reservationId, productId, reason);
        var envelope = EventEnvelope.of(EventTypes.INVENTORY_FAILED, reservationId, payload);
        outboxEventRepository.save(OutboxEvent.create(
                AGGREGATE_TYPE, reservationId, EventTypes.INVENTORY_FAILED, serialize(envelope)));
    }

    private String serialize(EventEnvelope<?> envelope) {
        try {
            return OBJECT_MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            // A serialization failure here rolls back the whole calling
            // transaction -- we'd rather fail loudly than silently lose an event.
            throw new IllegalStateException("Failed to serialize outbox event", e);
        }
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
