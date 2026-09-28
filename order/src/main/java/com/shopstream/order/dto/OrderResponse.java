package com.shopstream.order.dto;

import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
        String id,
        UUID customerId,
        UUID productId,
        int quantity,
        String status,
        Instant createdAt) {
}
