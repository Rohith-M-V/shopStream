package com.shopstream.notification.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shopstream.common.events.EventTypes;
import com.shopstream.common.events.Topics;
import com.shopstream.notification.dto.NotificationResponse;
import com.shopstream.notification.idempotency.ProcessedEventStore;
import com.shopstream.notification.notification.NotificationRepository;
import com.shopstream.notification.support.AbstractIntegrationTest;
import com.shopstream.notification.support.TestJwtSupport;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Phase 6a: the idempotent consumer. Events are published straight to
 * order-events, so this doesn't depend on the order service running.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderEventsListenerIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private ProcessedEventStore processedEventStore;

    private void publish(UUID eventId, String orderId, UUID customerId, String eventType) {
        String json = """
                {"eventId":"%s","type":"%s","version":1,"occurredAt":"%s","aggregateId":"%s",
                 "payload":{"orderId":"%s","customerId":"%s","productId":"%s","quantity":2,"createdAt":"%s"}}
                """.formatted(eventId, eventType, Instant.now(), orderId,
                orderId, customerId, UUID.randomUUID(), Instant.now());

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(Topics.ORDER_EVENTS, orderId, json));
        }
    }

    private ResponseEntity<List<NotificationResponse>> fetchNotifications(String orderId, UUID customerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.token(customerId, "CUSTOMER"));
        return restTemplate.exchange("/api/notifications/orders/" + orderId, HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<>() {
                });
    }

    private ResponseEntity<String> fetch(String orderId, UUID customerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.token(customerId, "CUSTOMER"));
        return restTemplate.exchange("/api/notifications/orders/" + orderId, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    @Test
    void orderConfirmedCreatesANotificationTheOwnerCanRead() {
        UUID customerId = UUID.randomUUID();
        String orderId = "order-" + UUID.randomUUID();

        publish(UUID.randomUUID(), orderId, customerId, EventTypes.ORDER_CONFIRMED);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var response = fetchNotifications(orderId, customerId);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(1);
            assertThat(response.getBody().get(0).type()).isEqualTo("ORDER_CONFIRMED");
        });
    }

    @Test
    void aRedeliveredEventProducesExactlyOneNotification() throws InterruptedException {
        UUID customerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String orderId = "order-" + UUID.randomUUID();

        // Same eventId three times: what Kafka's at-least-once delivery can do.
        publish(eventId, orderId, customerId, EventTypes.ORDER_CANCELLED);
        publish(eventId, orderId, customerId, EventTypes.ORDER_CANCELLED);
        publish(eventId, orderId, customerId, EventTypes.ORDER_CANCELLED);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(notificationRepository.countByEventId(eventId)).isEqualTo(1));

        // Give the duplicates time to be (wrongly) processed, then confirm they weren't.
        Thread.sleep(3000);
        assertThat(notificationRepository.countByEventId(eventId)).isEqualTo(1);
        assertThat(fetchNotifications(orderId, customerId).getBody()).hasSize(1);
        assertThat(processedEventStore.count()).isPositive();
    }

    @Test
    void differentEventsForTheSameOrderAreEachDelivered() {
        UUID customerId = UUID.randomUUID();
        String orderId = "order-" + UUID.randomUUID();

        publish(UUID.randomUUID(), orderId, customerId, EventTypes.ORDER_CONFIRMED);
        publish(UUID.randomUUID(), orderId, customerId, EventTypes.ORDER_CANCELLED);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(fetchNotifications(orderId, customerId).getBody()).hasSize(2));
    }

    @Test
    void nonTerminalEventsAreIgnored() throws InterruptedException {
        UUID customerId = UUID.randomUUID();
        String orderId = "order-" + UUID.randomUUID();

        publish(UUID.randomUUID(), orderId, customerId, EventTypes.ORDER_CREATED);
        publish(UUID.randomUUID(), orderId, customerId, EventTypes.PAYMENT_REQUESTED);
        Thread.sleep(3000);

        assertThat(fetch(orderId, customerId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anotherCustomerCannotSeeTheNotification() {
        UUID owner = UUID.randomUUID();
        String orderId = "order-" + UUID.randomUUID();
        publish(UUID.randomUUID(), orderId, owner, EventTypes.ORDER_CONFIRMED);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(fetchNotifications(orderId, owner).getStatusCode()).isEqualTo(HttpStatus.OK));

        assertThat(fetch(orderId, UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void requestsWithoutATokenAreRejected() {
        var response = restTemplate.getForEntity("/api/notifications/orders/anything", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
