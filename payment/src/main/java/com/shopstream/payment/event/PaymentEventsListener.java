package com.shopstream.payment.event;

import com.shopstream.common.events.EventTypes;
import com.shopstream.common.events.Topics;
import com.shopstream.payment.web.PaymentService;
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
 * Consumes the SAME order-events topic inventory listens to, but cares about
 * a different event type: PaymentRequested, not OrderCreated. Everything
 * else on this topic (OrderCreated, InventoryReleaseRequested) is ignored
 * here, same forward-compatible "ignore what you don't recognise" pattern
 * used by every other listener in this project.
 *
 * <p>Note there's no business-exception catch block here, unlike inventory's
 * OrderEventsListener. That's not an oversight -- PaymentService models a
 * decline as a normal return value (see PaymentResult), not a thrown
 * exception, so there's nothing to catch. @RetryableTopic still guards
 * against genuinely unexpected failures (a DB outage, a bug), which WOULD
 * throw and DO need retrying.
 */
@Component
public class PaymentEventsListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventsListener.class);
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private final PaymentService paymentService;

    public PaymentEventsListener(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @RetryableTopic(
            attempts = "4",
            backOff = @BackOff(delay = 1000, multiplier = 2.0),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE)
    @KafkaListener(topics = Topics.ORDER_EVENTS, groupId = "payment-service")
    public void onOrderEvent(String message) {
        IncomingOrderEvent event = OBJECT_MAPPER.readValue(message, IncomingOrderEvent.class);

        if (!EventTypes.PAYMENT_REQUESTED.equals(event.type())) {
            return;
        }

        IncomingOrderEvent.Payload payload = event.payload();
        paymentService.processPayment(payload.orderId(), payload.customerId(), payload.productId(),
                payload.quantity());
    }

    @DltHandler
    public void onDeadLetter(String message) {
        log.error("Payment request exhausted all retries and was sent to the dead-letter topic: {}", message);
    }
}
