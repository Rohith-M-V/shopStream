package com.shopstream.order.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shopstream.common.events.EventTypes;
import com.shopstream.common.events.Topics;
import com.shopstream.common.web.HeaderNames;
import com.shopstream.order.dto.CreateOrderRequest;
import com.shopstream.order.dto.OrderResponse;
import com.shopstream.order.support.AbstractIntegrationTest;
import com.shopstream.order.support.TestJwtSupport;
import java.time.Duration;
import java.time.Instant;
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
 * Proves the saga from order's side: given a real InventoryReserved or
 * InventoryFailed event, does the order actually transition? This never runs
 * the inventory service -- publishing the event directly is the point, since
 * order's consumer should react correctly to any conforming event regardless
 * of who produced it.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryEventsListenerIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

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

    private void publishInventoryEvent(String orderId, String eventType, Integer quantity, String reason) {
        String reasonField = reason == null ? "null" : "\"" + reason + "\"";
        Integer qty = quantity == null ? null : quantity;
        String json = """
                {"eventId":"%s","type":"%s","version":1,"occurredAt":"%s","aggregateId":"%s",
                 "payload":{"orderId":"%s","productId":"%s","quantity":%s,"reason":%s}}
                """.formatted(UUID.randomUUID(), eventType, Instant.now(), orderId,
                orderId, UUID.randomUUID(), qty, reasonField);

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(Topics.INVENTORY_EVENTS, orderId, json));
        }
    }

    private OrderResponse getOrder(String orderId, UUID customerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.token(customerId, "CUSTOMER"));
        return restTemplate.exchange("/api/orders/" + orderId, HttpMethod.GET, new HttpEntity<>(headers),
                OrderResponse.class).getBody();
    }

    @Test
    void inventoryReservedMovesOrderToPaymentPending() {
        UUID customerId = UUID.randomUUID();
        String orderId = createOrder(customerId);

        publishInventoryEvent(orderId, EventTypes.INVENTORY_RESERVED, 2, null);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getOrder(orderId, customerId).status()).isEqualTo("PAYMENT_PENDING"));
    }

    @Test
    void inventoryFailedCancelsTheOrder() {
        UUID customerId = UUID.randomUUID();
        String orderId = createOrder(customerId);

        publishInventoryEvent(orderId, EventTypes.INVENTORY_FAILED, null, "insufficient stock");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getOrder(orderId, customerId).status()).isEqualTo("CANCELLED"));
    }

    @Test
    void aRedeliveredInventoryReservedEventIsIgnoredOnceOrderHasMovedOn() {
        UUID customerId = UUID.randomUUID();
        String orderId = createOrder(customerId);

        publishInventoryEvent(orderId, EventTypes.INVENTORY_FAILED, null, "insufficient stock");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getOrder(orderId, customerId).status()).isEqualTo("CANCELLED"));

        // A redelivered (duplicate) InventoryReserved arriving AFTER the order
        // already moved to CANCELLED must not resurrect or overwrite it --
        // this is the idempotency guard in OrderService#applyTransitionIfPending.
        publishInventoryEvent(orderId, EventTypes.INVENTORY_RESERVED, 2, null);

        // There's nothing to "await" for a no-op, so give the consumer a brief
        // window to (wrongly) process it, then assert the status held.
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(getOrder(orderId, customerId).status()).isEqualTo("CANCELLED");
    }
}
