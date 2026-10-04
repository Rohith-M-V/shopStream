package com.shopstream.order.order;

import java.time.Instant;
import java.util.UUID;

/**
 * The payload shape for every event order publishes onto order-events:
 * OrderCreated (Phase 3), PaymentRequested and InventoryReleaseRequested
 * (both Phase 5). One shape for all three, rather than a separate payload
 * class per event type, because order always has the full {@link Order}
 * entity loaded at the point it publishes any of them -- there's no cost to
 * including every field, and it means every consumer on this topic
 * (inventory, payment) can deserialize with one record type and branch on
 * the event's own {@code type} field, rather than needing to sniff the shape
 * before knowing how to parse it.
 *
 * <p>(Originally named OrderCreatedPayload, when it only had one use.)
 */
public record OrderEventPayload(
        String orderId,
        UUID customerId,
        UUID productId,
        int quantity,
        Instant createdAt) {

    public static OrderEventPayload of(Order order) {
        return new OrderEventPayload(
                order.getId(), order.getCustomerId(), order.getProductId(), order.getQuantity(),
                order.getCreatedAt());
    }
}
