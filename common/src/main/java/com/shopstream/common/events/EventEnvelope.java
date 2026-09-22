package com.shopstream.common.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Every event on Kafka is wrapped in this envelope.
 * <ul>
 *   <li>eventId: unique per event, used by consumers for de-duplication (idempotency)</li>
 *   <li>type: one of {@link EventTypes}</li>
 *   <li>version: schema version of the payload, so we can evolve events safely</li>
 *   <li>aggregateId: e.g. the orderId; also used as the Kafka record key to keep per-order ordering</li>
 * </ul>
 */
public record EventEnvelope<T>(
        UUID eventId,
        String type,
        int version,
        Instant occurredAt,
        String aggregateId,
        T payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(payload, "payload");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
    }

    public static <T> EventEnvelope<T> of(String type, String aggregateId, T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), type, 1, Instant.now(), aggregateId, payload);
    }
}
