package com.shopstream.inventory.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The shape inventory expects on the order-events topic. order now publishes
 * THREE event types here (OrderCreated, PaymentRequested,
 * InventoryReleaseRequested), all sharing this one payload shape -- see
 * order's own OrderEventPayload for why. Inventory only acts on
 * OrderCreated and InventoryReleaseRequested; PaymentRequested is ignored
 * here the same way other unrecognised types would be.
 *
 * <p>This is a DELIBERATE duplicate of order's own OrderEventPayload, not a
 * shared class pulled from a common library -- sharing one Java class across
 * two services' Kafka contract would couple their deployments together.
 * (Originally named IncomingOrderCreatedEvent, when it only had one use.)
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
