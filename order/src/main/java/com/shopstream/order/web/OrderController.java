package com.shopstream.order.web;

import com.shopstream.common.web.HeaderNames;
import com.shopstream.order.dto.CreateOrderRequest;
import com.shopstream.order.dto.OrderResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @RequestHeader(HeaderNames.IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID customerId = customerIdFrom(jwt);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                orderService.createOrder(idempotencyKey, customerId, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        UUID customerId = customerIdFrom(jwt);
        return ResponseEntity.ok(orderService.getOrder(id, customerId));
    }

    /**
     * The customer id always comes from the validated JWT's subject claim,
     * never from anything the client puts in the request body -- otherwise
     * any authenticated user could create or read orders under someone
     * else's identity just by naming a different customerId in the payload.
     */
    private UUID customerIdFrom(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
