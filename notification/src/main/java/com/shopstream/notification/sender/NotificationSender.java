package com.shopstream.notification.sender;

import com.shopstream.notification.notification.Notification;

/**
 * The delivery channel behind the service, same idea as payment's
 * PaymentProvider: NotificationService never knows whether this is a log line,
 * an SMTP mailer or a push service. A real channel would implement this
 * interface and be selected via {@code app.notification.sender}.
 */
public interface NotificationSender {

    void send(Notification notification);
}
