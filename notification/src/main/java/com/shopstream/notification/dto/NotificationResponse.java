package com.shopstream.notification.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String orderId,
        String type,
        String subject,
        String body,
        Instant createdAt) {
}
