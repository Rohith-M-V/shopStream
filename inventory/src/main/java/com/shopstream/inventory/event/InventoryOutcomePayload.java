package com.shopstream.inventory.event;

import java.util.UUID;

/**
 * One shape covers both outcomes inventory reports back: InventoryReserved
 * (quantity set, reason null) and InventoryFailed (quantity null, reason
 * set). The event's "type" field is what actually distinguishes them; this
 * payload doesn't need its own subtype per outcome.
 */
public record InventoryOutcomePayload(
        String orderId,
        UUID productId,
        Integer quantity,
        String reason) {

    public static InventoryOutcomePayload reserved(String orderId, UUID productId, int quantity) {
        return new InventoryOutcomePayload(orderId, productId, quantity, null);
    }

    public static InventoryOutcomePayload failed(String orderId, UUID productId, String reason) {
        return new InventoryOutcomePayload(orderId, productId, null, reason);
    }
}
