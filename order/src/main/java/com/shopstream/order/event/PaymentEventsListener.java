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
 * The final consumer in the saga: order reacts to payment's outcome by
 * confirming or cancelling the order (and, on failure, compensating). See
 * {@link com.shopstream.order.web.OrderService#handlePaymentSucceeded} and
 * {@code #handlePaymentFailed}.
 */
@Component
public class PaymentEventsListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventsListener.class);
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private final OrderService orderService;

    public PaymentEventsListener(OrderService orderService) {
        this.orderService = orderService;
    }

    @RetryableTopic(
            attempts = "4",
            backOff = @BackOff(delay = 1000, multiplier = 2.0),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE)
    @KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = "order-service")
    public void onPaymentEvent(String message) {
        IncomingPaymentEvent event = OBJECT_MAPPER.readValue(message, IncomingPaymentEvent.class);
        IncomingPaymentEvent.Payload payload = event.payload();

        if (EventTypes.PAYMENT_SUCCEEDED.equals(event.type())) {
            orderService.handlePaymentSucceeded(payload.orderId());
        } else if (EventTypes.PAYMENT_FAILED.equals(event.type())) {
            orderService.handlePaymentFailed(payload.orderId(), payload.reason());
        }
    }

    @DltHandler
    public void onDeadLetter(String message) {
        log.error("Payment event exhausted all retries and was sent to the dead-letter topic: {}", message);
    }
}
