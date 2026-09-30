package com.shopstream.order.event;

import com.shopstream.common.events.EventTypes;
import com.shopstream.common.events.Topics;
import com.shopstream.order.web.OrderService;
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
 * The other half of the saga: order reacts to inventory's outcome events by
 * moving its own order forward or cancelling it. See
 * {@link com.shopstream.order.web.OrderService#handleInventoryReserved} and
 * {@code #handleInventoryFailed} for the actual state-transition logic and
 * its idempotency guard.
 */
@Component
public class InventoryEventsListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventsListener.class);
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private final OrderService orderService;

    public InventoryEventsListener(OrderService orderService) {
        this.orderService = orderService;
    }

    @RetryableTopic(
            attempts = "4",
            backOff = @BackOff(delay = 1000, multiplier = 2.0),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE)
    @KafkaListener(topics = Topics.INVENTORY_EVENTS, groupId = "order-service")
    public void onInventoryEvent(String message) {
        IncomingInventoryEvent event = OBJECT_MAPPER.readValue(message, IncomingInventoryEvent.class);
        IncomingInventoryEvent.Payload payload = event.payload();

        if (EventTypes.INVENTORY_RESERVED.equals(event.type())) {
            orderService.handleInventoryReserved(payload.orderId());
        } else if (EventTypes.INVENTORY_FAILED.equals(event.type())) {
            orderService.handleInventoryFailed(payload.orderId(), payload.reason());
        }
        // Any other event type on this topic is ignored -- forward compatible
        // with event types this listener doesn't know about yet.
    }

    @DltHandler
    public void onDeadLetter(String message) {
        log.error("Inventory event exhausted all retries and was sent to the dead-letter topic: {}", message);
    }
}
