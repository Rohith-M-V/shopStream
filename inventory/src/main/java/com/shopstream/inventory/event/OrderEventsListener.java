package com.shopstream.inventory.event;

import com.shopstream.common.events.EventTypes;
import com.shopstream.common.events.Topics;
import com.shopstream.inventory.exception.InsufficientStockException;
import com.shopstream.inventory.exception.ProductNotFoundException;
import com.shopstream.inventory.exception.ReservationNotFoundException;
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
 * Consumes order-events and reacts to two event types: OrderCreated (attempt
 * a reservation) and, as of Phase 5, InventoryReleaseRequested (release a
 * reservation already made -- the compensating transaction for when payment
 * fails after inventory already succeeded). PaymentRequested also lands on
 * this topic but is irrelevant here and is ignored, same as any future
 * unrecognised type.
 *
 * <p>The distinction that matters most in this class: {@link
 * InsufficientStockException}, {@link ProductNotFoundException}, and (for the
 * release path) {@link ReservationNotFoundException} are BUSINESS outcomes --
 * caught here, never allowed to propagate and trigger a pointless retry.
 * Retrying "insufficient stock" can never succeed. Any OTHER exception (a DB
 * outage, a bug) is genuinely transient or unexpected and IS allowed to
 * propagate, which is what triggers {@code @RetryableTopic}'s retry-then-DLT
 * behavior below.
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
        IncomingOrderEvent event = OBJECT_MAPPER.readValue(message, IncomingOrderEvent.class);
        IncomingOrderEvent.Payload payload = event.payload();

        if (EventTypes.ORDER_CREATED.equals(event.type())) {
            handleOrderCreated(payload);
        } else if (EventTypes.INVENTORY_RELEASE_REQUESTED.equals(event.type())) {
            handleReleaseRequested(payload);
        }
        // PAYMENT_REQUESTED (and anything else future) is ignored here.
    }

    private void handleOrderCreated(IncomingOrderEvent.Payload payload) {
        try {
            productService.reserveStock(payload.productId(), payload.orderId(), payload.quantity());
            log.info("Reserved stock for order {}", payload.orderId());
        } catch (InsufficientStockException | ProductNotFoundException e) {
            log.info("Could not reserve stock for order {}: {}", payload.orderId(), e.getMessage());
            productService.recordReservationFailure(payload.productId(), payload.orderId(), e.getMessage());
        }
    }

    private void handleReleaseRequested(IncomingOrderEvent.Payload payload) {
        try {
            productService.releaseStock(payload.orderId());
            log.info("Released reservation for order {} (payment failed, compensating)", payload.orderId());
        } catch (ReservationNotFoundException e) {
            // Should not happen in normal operation -- a release is only ever
            // requested for an order that reached PAYMENT_PENDING, which means
            // a reservation must have existed. Treated as a business no-op
            // rather than crashing into a pointless retry, since retrying
            // can't make a real reservation appear.
            log.warn("Release requested for order {} but no reservation was found: {}",
                    payload.orderId(), e.getMessage());
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
