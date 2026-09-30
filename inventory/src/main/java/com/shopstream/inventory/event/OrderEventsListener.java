package com.shopstream.inventory.event;

import com.shopstream.common.events.EventTypes;
import com.shopstream.common.events.Topics;
import com.shopstream.inventory.exception.InsufficientStockException;
import com.shopstream.inventory.exception.ProductNotFoundException;
import com.shopstream.inventory.web.ProductService;
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
 * Consumes order-events and reacts to OrderCreated by attempting a stock
 * reservation. This is the saga in action: order and inventory never call
 * each other directly, they only react to events the other one published.
 *
 * <p>The distinction that matters most in this class: {@link
 * InsufficientStockException} and {@link ProductNotFoundException} are
 * BUSINESS outcomes -- caught here, and turned into an InventoryFailed event,
 * not allowed to propagate. Retrying "insufficient stock" can never succeed,
 * so letting Kafka's retry machinery repeatedly redeliver it would just waste
 * cycles and delay the InventoryFailed event the order service is waiting
 * for. Any OTHER exception (a DB outage, a bug) is a genuinely transient or
 * unexpected failure and is allowed to propagate, which is what triggers
 * {@code @RetryableTopic}'s retry-then-DLT behavior below.
 */
@Component
public class OrderEventsListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsListener.class);
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private final ProductService productService;

    public OrderEventsListener(ProductService productService) {
        this.productService = productService;
    }

    @RetryableTopic(
            attempts = "4",
            backOff = @BackOff(delay = 1000, multiplier = 2.0),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE)
    @KafkaListener(topics = Topics.ORDER_EVENTS, groupId = "inventory-service")
    public void onOrderEvent(String message) {
        IncomingOrderCreatedEvent event = OBJECT_MAPPER.readValue(message, IncomingOrderCreatedEvent.class);

        // This service only cares about ORDER_CREATED; other event types may
        // land on this same topic in the future, and should be ignored here
        // rather than treated as an error.
        if (!EventTypes.ORDER_CREATED.equals(event.type())) {
            return;
        }

        IncomingOrderCreatedEvent.Payload payload = event.payload();
        try {
            productService.reserveStock(payload.productId(), payload.orderId(), payload.quantity());
            log.info("Reserved stock for order {}", payload.orderId());
        } catch (InsufficientStockException | ProductNotFoundException e) {
            log.info("Could not reserve stock for order {}: {}", payload.orderId(), e.getMessage());
            productService.recordReservationFailure(payload.productId(), payload.orderId(), e.getMessage());
        }
    }

    /**
     * Reached only after all {@code @RetryableTopic} attempts above are
     * exhausted for a genuinely unexpected failure -- e.g. the database was
     * down for the whole retry window. In a real system this would page
     * someone or write to an alerting sink; here, logging loudly is the
     * honest stand-in for that.
     */
    @DltHandler
    public void onDeadLetter(String message) {
        log.error("Order event exhausted all retries and was sent to the dead-letter topic: {}", message);
    }
}
