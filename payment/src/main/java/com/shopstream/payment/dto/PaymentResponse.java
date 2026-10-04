package com.shopstream.payment.dto;

import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        String orderId,
        UUID customerId,
        UUID productId,
        int quantity,
        String status,
        String reason,
        Instant createdAt) {
}
