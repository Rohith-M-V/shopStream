package com.shopstream.notification.sender;

import com.shopstream.notification.notification.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Default sender: writes the "email" to the log. Needs no infrastructure. */
@Component
@ConditionalOnProperty(name = "app.notification.sender", havingValue = "logging", matchIfMissing = true)
public class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(Notification notification) {
        log.info("[NOTIFY] customer={} order={} subject=\"{}\" body=\"{}\"",
                notification.getCustomerId(), notification.getOrderId(),
                notification.getSubject(), notification.getBody());
    }
}
