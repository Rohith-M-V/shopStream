package com.shopstream.payment.web;

import com.shopstream.payment.dto.PaymentResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately read-only: there is no POST endpoint here. A payment should
 * only ever be triggered by the saga reacting to a PaymentRequested event,
 * never created directly by an HTTP caller -- letting an arbitrary client
 * call "charge this order" over REST would bypass the whole
 * order-created-then-inventory-reserved sequence this service exists to come
 * after.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<PaymentResponse> get(@PathVariable String orderId, @AuthenticationPrincipal Jwt jwt) {
        UUID customerId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(paymentService.getPayment(orderId, customerId));
    }
}
