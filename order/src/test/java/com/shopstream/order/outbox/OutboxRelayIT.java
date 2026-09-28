package com.shopstream.order.outbox;

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
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The test that actually earns the "transactional outbox" resume line: it
 * creates an order over HTTP, then checks BOTH that the event really arrived
 * on a real Kafka broker AND that the outbox row got marked published --
 * the two halves of the pattern.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxRelayIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    void creatingAnOrderPublishesAnOrderCreatedEventToKafkaAndMarksTheOutboxRowPublished() {
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String orderId = "order-" + UUID.randomUUID();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.token(customerId, "CUSTOMER"));
        headers.set(HeaderNames.IDEMPOTENCY_KEY, orderId);
        var response = restTemplate.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(new CreateOrderRequest(productId, 4), headers), OrderResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // The relay polls on a schedule (1s in application.yml), so the event
        // shows up "soon", not instantly -- hence Awaitility rather than a
        // single assertion right after the request.
        AtomicReference<String> receivedValue = new AtomicReference<>();
        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(List.of(Topics.ORDER_EVENTS));
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    // Other tests in this class run share the broker, so filter by key.
                    if (orderId.equals(record.key())) {
                        receivedValue.set(record.value());
                    }
                }
                assertThat(receivedValue.get()).isNotNull();
            });
        }

        // Asserting on the raw JSON string (the mapper writes compact JSON, no
        // spaces) keeps this test independent of any particular JSON library API.
        String json = receivedValue.get();
        assertThat(json).contains("\"type\":\"" + EventTypes.ORDER_CREATED + "\"");
        assertThat(json).contains("\"aggregateId\":\"" + orderId + "\"");
        assertThat(json).contains("\"orderId\":\"" + orderId + "\"");
        assertThat(json).contains("\"customerId\":\"" + customerId + "\"");
        assertThat(json).contains("\"productId\":\"" + productId + "\"");
        assertThat(json).contains("\"quantity\":4");

        // The other half of the pattern: once Kafka acknowledged the message,
        // the relay must have marked the outbox row published.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var row = outboxEventRepository.findAll().stream()
                    .filter(e -> e.getAggregateId().equals(orderId))
                    .findFirst();
            assertThat(row).isPresent();
            assertThat(row.get().isPublished()).isTrue();
        });
    }

    private KafkaConsumer<String, String> newConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }
}
