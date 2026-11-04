package com.shopstream.notification.notification;

import com.shopstream.common.events.EventTypes;
import com.shopstream.notification.dto.NotificationResponse;
import com.shopstream.notification.event.IncomingOrderEvent;
import com.shopstream.notification.event.OrderEventsListener;
import com.shopstream.notification.exception.NotificationNotFoundException;
import com.shopstream.notification.idempotency.ProcessedEventStore;
import com.shopstream.notification.sender.NotificationSender;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final ProcessedEventStore processedEventStore;
    private final NotificationRepository notificationRepository;
    private final NotificationSender sender;

    public NotificationService(ProcessedEventStore processedEventStore,
            NotificationRepository notificationRepository, NotificationSender sender) {
        this.processedEventStore = processedEventStore;
        this.notificationRepository = notificationRepository;
        this.sender = sender;
    }

    /**
     * Claims the event, records the notification and sends it, all in one
     * transaction. If anything after the claim throws, the claim rolls back
     * with it and the retry topic gets a clean second attempt; a redelivered
     * event that was already committed is skipped.
     *
     * <p>Honest limit: the send itself is an external side effect that a DB
     * rollback can't undo, so a crash between "sent" and "commit" can still
     * produce one duplicate send. That is inherent to at-least-once delivery;
     * a real provider would also be given the eventId as its own idempotency key.
     */
    @Transactional
    public void handle(IncomingOrderEvent event) {
        NotificationType type = toType(event.type());
        if (type == null) {
            return; // not a terminal outcome we notify about
        }

        if (!processedEventStore.tryClaim(OrderEventsListener.CONSUMER_GROUP, event.eventId())) {
            log.info("Event {} already processed, skipping redelivery", event.eventId());
            return;
        }

        IncomingOrderEvent.Payload payload = event.payload();
        String subject = type == NotificationType.ORDER_CONFIRMED
                ? "Your order is confirmed"
                : "Your order was cancelled";
        String body = type == NotificationType.ORDER_CONFIRMED
                ? "Order %s has been confirmed. Thanks for shopping with ShopStream.".formatted(payload.orderId())
                : "Order %s was cancelled. Any payment hold or stock reservation has been released."
                        .formatted(payload.orderId());

        Notification notification = Notification.create(event.eventId(), payload.orderId(),
                payload.customerId(), type, subject, body);
        notificationRepository.save(notification);
        sender.send(notification);
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> getForOrder(String orderId, UUID requestingCustomerId) {
        List<Notification> found = notificationRepository
                .findByOrderIdAndCustomerIdOrderByCreatedAtAsc(orderId, requestingCustomerId);
        // Same "don't confirm what you can't prove the caller may see" rule as order/payment:
        // someone else's order and an unknown order look identical.
        if (found.isEmpty()) {
            throw new NotificationNotFoundException(orderId);
        }
        return found.stream()
                .map(n -> new NotificationResponse(n.getId(), n.getOrderId(), n.getType().name(),
                        n.getSubject(), n.getBody(), n.getCreatedAt()))
                .toList();
    }

    private static NotificationType toType(String eventType) {
        if (EventTypes.ORDER_CONFIRMED.equals(eventType)) {
            return NotificationType.ORDER_CONFIRMED;
        }
        if (EventTypes.ORDER_CANCELLED.equals(eventType)) {
            return NotificationType.ORDER_CANCELLED;
        }
        return null;
    }
}
