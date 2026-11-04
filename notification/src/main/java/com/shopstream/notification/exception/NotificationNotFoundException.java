package com.shopstream.notification.exception;

public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException(String orderId) {
        super("No notifications found for order: " + orderId);
    }
}
