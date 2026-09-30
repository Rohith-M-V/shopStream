package com.shopstream.inventory.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The shape inventory expects to receive on the order-events topic, matching
 * field-for-field what order's EventEnvelope&lt;OrderCreatedPayload&gt;
 * actually serializes to. This is a DELIBERATE duplicate of order's own
 * OrderCreatedPayload, not a shared class pulled from a common library.
 *
 * <p>That's a real design choice, not an oversight: sharing one Java class
 * across two services' Kafka contract would couple their deployments
 * together (a field renamed in order's class would silently break inventory
 * at compile time, or worse, at runtime if versions drift). Each consumer
 * instead owns its own idea of the wire contract it depends on -- the two
 * shapes just need to agree on JSON field names, not share code.
 */
public record IncomingOrderCreatedEvent(
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
