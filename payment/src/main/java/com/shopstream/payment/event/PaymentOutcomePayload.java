package com.shopstream.payment.event;

/**
 * One shape for both outcomes, same idea as inventory's InventoryOutcomePayload:
 * {@code reason} is null on success, set on failure; the event's own
 * {@code type} field is what actually distinguishes the two.
 */
public record PaymentOutcomePayload(String orderId, String reason) {

    public static PaymentOutcomePayload succeeded(String orderId) {
        return new PaymentOutcomePayload(orderId, null);
    }

    public static PaymentOutcomePayload failed(String orderId, String reason) {
        return new PaymentOutcomePayload(orderId, reason);
    }
}
