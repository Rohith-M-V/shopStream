package com.shopstream.inventory.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shopstream.common.events.EventTypes;
import com.shopstream.common.events.Topics;
import com.shopstream.inventory.dto.CreateProductRequest;
import com.shopstream.inventory.dto.ProductResponse;
import com.shopstream.inventory.support.AbstractIntegrationTest;
import com.shopstream.inventory.support.TestJwtSupport;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;

/**
 * This test drives the saga from the OTHER side of what OutboxRelayIT (order
 * module) proved: given a real OrderCreated event on the real topic, does
 * inventory actually react correctly? It never calls the order service --
 * publishing the event directly is the point, since inventory should never
 * need to know or care who produced it.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderEventsListenerIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private ProductResponse createProduct(String sku, int initialStock) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.token("ADMIN"));
        var request = new CreateProductRequest(sku, "Saga test product", null, new BigDecimal("9.99"), initialStock);
        return restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(request, headers),
                ProductResponse.class).getBody();
    }

    private void publishOrderCreated(String orderId, UUID customerId, UUID productId, int quantity) {
        String json = """
                {"eventId":"%s","type":"%s","version":1,"occurredAt":"%s","aggregateId":"%s",
                 "payload":{"orderId":"%s","customerId":"%s","productId":"%s","quantity":%d,"createdAt":"%s"}}
                """.formatted(UUID.randomUUID(), EventTypes.ORDER_CREATED, Instant.now(), orderId,
                orderId, customerId, productId, quantity, Instant.now());

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(Topics.ORDER_EVENTS, orderId, json));
        }
    }

    private KafkaConsumer<String, String> newInventoryEventsConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(Topics.INVENTORY_EVENTS));
        return consumer;
    }

    @Test
    void orderCreatedWithSufficientStockReservesAndPublishesInventoryReserved() {
        ProductResponse product = createProduct("SAGA-SKU-1", 10);
        String orderId = "order-" + UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        publishOrderCreated(orderId, customerId, product.id(), 3);

        AtomicReference<String> received = new AtomicReference<>();
        try (KafkaConsumer<String, String> consumer = newInventoryEventsConsumer()) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (orderId.equals(record.key())) {
                        received.set(record.value());
                    }
                }
                assertThat(received.get()).isNotNull();
            });
        }

        String json = received.get();
        assertThat(json).contains("\"type\":\"" + EventTypes.INVENTORY_RESERVED + "\"");
        assertThat(json).contains("\"orderId\":\"" + orderId + "\"");
        assertThat(json).contains("\"quantity\":3");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ProductResponse updated =
                    restTemplate.getForEntity("/api/products/" + product.id(), ProductResponse.class).getBody();
            assertThat(updated.stockQuantity()).isEqualTo(7);
        });
    }

    @Test
    void orderCreatedWithInsufficientStockPublishesInventoryFailedAndLeavesStockUnchanged() {
        ProductResponse product = createProduct("SAGA-SKU-2", 2);
        String orderId = "order-" + UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        publishOrderCreated(orderId, customerId, product.id(), 10);

        AtomicReference<String> received = new AtomicReference<>();
        try (KafkaConsumer<String, String> consumer = newInventoryEventsConsumer()) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (orderId.equals(record.key())) {
                        received.set(record.value());
                    }
                }
                assertThat(received.get()).isNotNull();
            });
        }

        String json = received.get();
        assertThat(json).contains("\"type\":\"" + EventTypes.INVENTORY_FAILED + "\"");
        assertThat(json).contains("\"orderId\":\"" + orderId + "\"");

        ProductResponse unchanged =
                restTemplate.getForEntity("/api/products/" + product.id(), ProductResponse.class).getBody();
        assertThat(unchanged.stockQuantity()).isEqualTo(2);
    }
}
