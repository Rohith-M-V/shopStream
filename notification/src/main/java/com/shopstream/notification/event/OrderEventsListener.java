package com.shopstream.notification.event;

import com.shopstream.common.events.Topics;
import com.shopstream.notification.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Listens to order-events, like inventory and payment, but only cares about
 * the terminal outcomes (OrderConfirmed / OrderCancelled). Everything else on
 * the topic is ignored inside {@link NotificationService}.
 *
 * <p>Same retry-then-DLT setup as every other listener. Duplicate delivery is
 * handled by the processed-events guard, not here.
 */
@Component
public class OrderEventsListener {

    public static final String CONSUMER_GROUP = "notification-service";

    private static final Logger log = LoggerFactory.getLogger(OrderEventsListener.class);
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private final NotificationService notificationService;

    public OrderEventsListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @RetryableTopic(
            attempts = "4",
            backOff = @BackOff(delay = 1000, multiplier = 2.0),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE)
    @KafkaListener(topics = Topics.ORDER_EVENTS, groupId = CONSUMER_GROUP)
    public void onOrderEvent(String message) {
        IncomingOrderEvent event = OBJECT_MAPPER.readValue(message, IncomingOrderEvent.class);
        notificationService.handle(event);
    }

    @DltHandler
    public void onDeadLetter(String message) {
        log.error("Order event exhausted all retries and was sent to the dead-letter topic: {}", message);
    }
}
