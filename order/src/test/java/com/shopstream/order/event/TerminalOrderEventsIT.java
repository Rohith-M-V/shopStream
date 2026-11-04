package com.shopstream.order.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shopstream.common.events.EventTypes;
import com.shopstream.common.events.Topics;
import com.shopstream.common.web.HeaderNames;
import com.shopstream.order.dto.CreateOrderRequest;
import com.shopstream.order.dto.OrderResponse;
import com.shopstream.order.outbox.OutboxEvent;
import com.shopstream.order.outbox.OutboxEventRepository;
import com.shopstream.order.support.AbstractIntegrationTest;
import com.shopstream.order.support.TestJwtSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Phase 6a: order now announces the saga's terminal outcomes (OrderConfirmed,
 * OrderCancelled) through its outbox, so downstream consumers such as the
 * notification service don't have to reverse-engineer them from
 * inventory/payment events.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TerminalOrderEventsIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private String createOrder(UUID customerId) {
        String orderId = "order-" + UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.token(customerId, "CUSTOMER"));
        headers.set(HeaderNames.IDEMPOTENCY_KEY, orderId);
        var response = restTemplate.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(new CreateOrderRequest(UUID.randomUUID(), 2), headers), OrderResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return orderId;
    }

    private void publish(String topic, String orderId, String eventType, String reason) {
        String reasonField = reason == null ? "null" : "\"" + reason + "\"";
        String json = """
                {"eventId":"%s","type":"%s","version":1,"occurredAt":"%s","aggregateId":"%s",
                 "payload":{"orderId":"%s","productId":"%s","quantity":2,"reason":%s}}
                """.formatted(UUID.randomUUID(), eventType, Instant.now(), orderId,
                orderId, UUID.randomUUID(), reasonField);

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, orderId, json));
        }
    }

    private List<String> eventTypesFor(String orderId) {
        return outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(orderId))
                .map(OutboxEvent::getEventType)
                .toList();
    }

    @Test
    void paymentSucceededPublishesOrderConfirmed() {
        String orderId = createOrder(UUID.randomUUID());
        publish(Topics.INVENTORY_EVENTS, orderId, EventTypes.INVENTORY_RESERVED, null);
        publish(Topics.PAYMENT_EVENTS, orderId, EventTypes.PAYMENT_SUCCEEDED, null);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(eventTypesFor(orderId)).contains(EventTypes.ORDER_CONFIRMED));
    }

    @Test
    void inventoryFailedPublishesOrderCancelled() {
        String orderId = createOrder(UUID.randomUUID());
        publish(Topics.INVENTORY_EVENTS, orderId, EventTypes.INVENTORY_FAILED, "insufficient stock");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(eventTypesFor(orderId)).contains(EventTypes.ORDER_CANCELLED));
    }

    @Test
    void paymentFailedPublishesOrderCancelledAndTheCompensation() {
        String orderId = createOrder(UUID.randomUUID());
        publish(Topics.INVENTORY_EVENTS, orderId, EventTypes.INVENTORY_RESERVED, null);
        publish(Topics.PAYMENT_EVENTS, orderId, EventTypes.PAYMENT_FAILED, "card declined");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(eventTypesFor(orderId)).contains(
                        EventTypes.ORDER_CANCELLED, EventTypes.INVENTORY_RELEASE_REQUESTED));
    }

    @Test
    void aRedeliveredPaymentSucceededDoesNotPublishASecondOrderConfirmed() throws InterruptedException {
        String orderId = createOrder(UUID.randomUUID());
        publish(Topics.INVENTORY_EVENTS, orderId, EventTypes.INVENTORY_RESERVED, null);
        publish(Topics.PAYMENT_EVENTS, orderId, EventTypes.PAYMENT_SUCCEEDED, null);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(eventTypesFor(orderId)).contains(EventTypes.ORDER_CONFIRMED));

        publish(Topics.PAYMENT_EVENTS, orderId, EventTypes.PAYMENT_SUCCEEDED, null);
        Thread.sleep(3000);

        assertThat(eventTypesFor(orderId).stream()
                .filter(EventTypes.ORDER_CONFIRMED::equals).count()).isEqualTo(1);
    }
}
