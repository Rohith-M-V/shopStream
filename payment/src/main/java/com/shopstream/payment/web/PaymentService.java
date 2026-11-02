package com.shopstream.payment.web;

import com.shopstream.common.events.EventEnvelope;
import com.shopstream.common.events.EventTypes;
import com.shopstream.payment.dto.PaymentResponse;
import com.shopstream.payment.event.PaymentOutcomePayload;
import com.shopstream.payment.exception.PaymentNotFoundException;
import com.shopstream.payment.outbox.OutboxEvent;
import com.shopstream.payment.outbox.OutboxEventRepository;
import com.shopstream.payment.payment.PaymentAttempt;
import com.shopstream.payment.payment.PaymentAttemptRepository;
import com.shopstream.payment.provider.PaymentProvider;
import com.shopstream.payment.provider.PaymentResult;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String AGGREGATE_TYPE = "Order";
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PaymentProvider paymentProvider;

    public PaymentService(PaymentAttemptRepository paymentAttemptRepository,
            OutboxEventRepository outboxEventRepository, PaymentProvider paymentProvider) {
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.paymentProvider = paymentProvider;
    }

    /**
     * Charges for an order, exactly once, no matter how many times this
     * method is called for the same order id.
     *
     * <p><b>Idempotency:</b> if a {@link PaymentAttempt} already exists for
     * this order id, this returns immediately without calling the provider
     * again -- this is what prevents a redelivered PaymentRequested event
     * (Kafka's at-least-once delivery guarantees this will happen eventually)
     * from charging a customer twice.
     *
     * <p>The charge outcome, the PaymentAttempt row, and the outbox event are
     * all written in this one transaction -- the same transactional-outbox
     * guarantee order and inventory rely on, just for payment's own outcome.
     */
    @Transactional
    public void processPayment(String orderId, UUID customerId, UUID productId, int quantity) {
        if (paymentAttemptRepository.existsById(orderId)) {
            log.info("Payment already processed for order {}, ignoring redelivery", orderId);
            return;
        }

        PaymentResult result = paymentProvider.charge(orderId, customerId, productId, quantity);

        PaymentAttempt attempt;
        String eventType;
        Object payload;
        if (result.success()) {
            attempt = PaymentAttempt.succeeded(orderId, customerId, productId, quantity);
            eventType = EventTypes.PAYMENT_SUCCEEDED;
            payload = PaymentOutcomePayload.succeeded(orderId);
        } else {
            attempt = PaymentAttempt.failed(orderId, customerId, productId, quantity, result.reason());
            eventType = EventTypes.PAYMENT_FAILED;
            payload = PaymentOutcomePayload.failed(orderId, result.reason());
        }
        paymentAttemptRepository.save(attempt);

        var envelope = EventEnvelope.of(eventType, orderId, payload);
        outboxEventRepository.save(OutboxEvent.create(AGGREGATE_TYPE, orderId, eventType, serialize(envelope)));
    }

    /**
     * Used by the PayPal webhook handler to decide whether a given order's
     * outcome has already been recorded via the synchronous charge path --
     * see PayPalWebhookController for why the webhook stops at this check
     * rather than attempting to reconcile a missing attempt itself.
     */
    @Transactional(readOnly = true)
    public boolean isAlreadyProcessed(String orderId) {
        return paymentAttemptRepository.existsById(orderId);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(String orderId, UUID requestingCustomerId) {
        PaymentAttempt attempt = paymentAttemptRepository.findById(orderId)
                .orElseThrow(() -> new PaymentNotFoundException(orderId));

        // Same "don't confirm what you can't prove the caller is allowed to
        // see" principle used everywhere else in this project.
        if (!attempt.getCustomerId().equals(requestingCustomerId)) {
            throw new PaymentNotFoundException(orderId);
        }
        return toResponse(attempt);
    }

    private String serialize(EventEnvelope<?> envelope) {
        try {
            return OBJECT_MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox event", e);
        }
    }

    private PaymentResponse toResponse(PaymentAttempt attempt) {
        return new PaymentResponse(
                attempt.getId(), attempt.getCustomerId(), attempt.getProductId(), attempt.getQuantity(),
                attempt.getStatus().name(), attempt.getReason(), attempt.getCreatedAt());
    }
}
