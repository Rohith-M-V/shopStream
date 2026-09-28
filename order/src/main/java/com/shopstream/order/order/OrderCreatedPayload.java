package com.shopstream.order.order;

import java.time.Instant;
import java.util.UUID;

/**
 * The payload of an OrderCreated event, wrapped inside a
 * {@link com.shopstream.common.events.EventEnvelope} before being written to
 * the outbox. Inventory (from Phase 4 onward) is the consumer that cares about
 * this shape.
 */
public record OrderCreatedPayload(
        String orderId,
        UUID customerId,
        UUID productId,
        int quantity,
        Instant createdAt) {
}
