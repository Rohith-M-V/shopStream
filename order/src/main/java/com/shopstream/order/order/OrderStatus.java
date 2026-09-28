package com.shopstream.order.order;

public enum OrderStatus {
    PENDING,

    // Not yet used in this phase -- these exist now so the schema and entity
    // don't need to change shape once the saga (Phase 4) starts driving these
    // transitions from Kafka events (inventory reserved/failed, payment
    // succeeded/failed).
    PAYMENT_PENDING,
    CONFIRMED,
    CANCELLED
}
