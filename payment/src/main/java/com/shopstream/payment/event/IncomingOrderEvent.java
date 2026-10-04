package com.shopstream.payment.event;

import java.time.Instant;
import java.util.UUID;

/**
 * order publishes THREE event types onto order-events (OrderCreated,
 * PaymentRequested, InventoryReleaseRequested), all sharing this one payload
 * shape -- order already has the full Order entity loaded whenever it
 * publishes any of them, so there's no cost to including every field even
 * though a given consumer only reads some of them. That uniformity is what
 * lets payment (and inventory) deserialize every message on this topic with
 * one record type and simply branch on {@code type}, rather than needing to
 * sniff the shape before knowing how to parse it.
 */
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
