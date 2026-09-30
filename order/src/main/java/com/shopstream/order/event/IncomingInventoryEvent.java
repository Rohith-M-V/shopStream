package com.shopstream.order.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The shape order expects on the inventory-events topic, matching what
 * inventory's InventoryOutcomePayload actually serializes to. Same
 * deliberate-duplication reasoning as inventory's own IncomingOrderCreatedEvent:
 * each service owns its idea of the events it consumes, rather than sharing
 * Java classes across the service boundary.
 */
public record IncomingInventoryEvent(
        UUID eventId,
        String type,
        int version,
        Instant occurredAt,
        String aggregateId,
        Payload payload) {

    public record Payload(
            String orderId,
            UUID productId,
            Integer quantity,
            String reason) {
    }
}
