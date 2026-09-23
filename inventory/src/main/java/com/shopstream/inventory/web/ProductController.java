package com.shopstream.inventory.web;

import com.shopstream.common.web.HeaderNames;
import com.shopstream.inventory.dto.CreateProductRequest;
import com.shopstream.inventory.dto.ProductResponse;
import com.shopstream.inventory.dto.ReservationResponse;
import com.shopstream.inventory.dto.ReserveStockRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.createProduct(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(productService.getProduct(id));
    }

    @GetMapping
    public ResponseEntity<Page<ProductResponse>> list(Pageable pageable) {
        return ResponseEntity.ok(productService.listProducts(pageable));
    }

    /**
     * The Idempotency-Key header carries the reservation id -- deliberately
     * the same header name and idea as auth or payment might use for their
     * own idempotent writes (see common's HeaderNames). Today the caller is
     * a human/Postman testing this by hand; once the order service exists,
     * the caller will be the saga, passing the order id as this key.
     */
    @PostMapping("/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID id,
            @RequestHeader(HeaderNames.IDEMPOTENCY_KEY) String reservationId,
            @Valid @RequestBody ReserveStockRequest request) {
        return ResponseEntity.ok(productService.reserveStock(id, reservationId, request.quantity()));
    }
}
