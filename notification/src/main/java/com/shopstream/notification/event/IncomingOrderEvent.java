package com.shopstream.notification.event;

import java.time.Instant;
import java.util.UUID;

/** Same shape every consumer of order-events uses (see payment's IncomingOrderEvent). */
public record IncomingOrderEvent(
        UUID eventId,
        String type,
        int version,
        Instant occurredAt,
        String aggregateId,
        Payload payload) {

    public record Payload(
            String orderId,
            UUID customerId,
            UUID productId,
            int quantity,
            Instant createdAt) {
    }
}
