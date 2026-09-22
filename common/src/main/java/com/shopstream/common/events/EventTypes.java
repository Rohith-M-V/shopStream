package com.shopstream.common.events;

public final class EventTypes {

    public static final String ORDER_CREATED = "OrderCreated";
    public static final String ORDER_CONFIRMED = "OrderConfirmed";
    public static final String ORDER_CANCELLED = "OrderCancelled";

    public static final String INVENTORY_RESERVED = "InventoryReserved";
    public static final String INVENTORY_FAILED = "InventoryFailed";
    public static final String INVENTORY_RELEASE_REQUESTED = "InventoryReleaseRequested";

    public static final String PAYMENT_REQUESTED = "PaymentRequested";
    public static final String PAYMENT_SUCCEEDED = "PaymentSucceeded";
    public static final String PAYMENT_FAILED = "PaymentFailed";

    private EventTypes() {
    }
}
