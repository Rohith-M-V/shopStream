package com.shopstream.order.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The shape order expects on payment-events, matching what payment's
 * PaymentOutcomePayload actually serializes to. Same deliberate-duplication
 * reasoning as IncomingInventoryEvent.
 */
public record IncomingPaymentEvent(
        UUID eventId,
        String type,
        int version,
        Instant occurredAt,
        String aggregateId,
        Payload payload) {

    public record Payload(String orderId, String reason) {
    }
}
