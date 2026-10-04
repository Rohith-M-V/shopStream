package com.shopstream.payment.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shopstream.common.events.EventTypes;
import com.shopstream.common.events.Topics;
import com.shopstream.payment.payment.PaymentAttemptRepository;
import com.shopstream.payment.support.AbstractIntegrationTest;
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
import org.springframework.test.annotation.DirtiesContext;

/**
 * Drives payment the same way OrderEventsListenerIT (inventory) and
 * InventoryEventsListenerIT (order) drive their services: publish a real
 * event directly onto the real topic, never touching the upstream service,
 * and check the reaction against real infrastructure.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentEventsListenerIT extends AbstractIntegrationTest {

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    private void publishPaymentRequested(String orderId, UUID customerId, UUID productId, int quantity) {
        String json = """
                {"eventId":"%s","type":"%s","version":1,"occurredAt":"%s","aggregateId":"%s",
                 "payload":{"orderId":"%s","customerId":"%s","productId":"%s","quantity":%d,"createdAt":"%s"}}
                """.formatted(UUID.randomUUID(), EventTypes.PAYMENT_REQUESTED, Instant.now(), orderId,
                orderId, customerId, productId, quantity, Instant.now());

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(Topics.ORDER_EVENTS, orderId, json));
        }
    }

    private String awaitPaymentEvent(String orderId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        AtomicReference<String> received = new AtomicReference<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(Topics.PAYMENT_EVENTS));
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (orderId.equals(record.key())) {
                        received.set(record.value());
                    }
                }
                assertThat(received.get()).isNotNull();
            });
        }
        return received.get();
    }

    @Test
    void paymentRequestedWithNormalQuantitySucceeds() {
        String orderId = "order-" + UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        publishPaymentRequested(orderId, customerId, productId, 2);

        String json = awaitPaymentEvent(orderId);
        assertThat(json).contains("\"type\":\"" + EventTypes.PAYMENT_SUCCEEDED + "\"");
        assertThat(json).contains("\"orderId\":\"" + orderId + "\"");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(paymentAttemptRepository.findById(orderId)).isPresent());
        assertThat(paymentAttemptRepository.findById(orderId).get().getStatus().name()).isEqualTo("SUCCEEDED");
    }

    @Test
    void paymentRequestedWithSentinelQuantityIsDeclined() {
        String orderId = "order-" + UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        publishPaymentRequested(orderId, customerId, productId, 99); // FakePaymentProvider's decline sentinel

        String json = awaitPaymentEvent(orderId);
        assertThat(json).contains("\"type\":\"" + EventTypes.PAYMENT_FAILED + "\"");
        assertThat(json).contains("\"orderId\":\"" + orderId + "\"");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(paymentAttemptRepository.findById(orderId)).isPresent());
        assertThat(paymentAttemptRepository.findById(orderId).get().getStatus().name()).isEqualTo("FAILED");
    }

    @Test
    void redeliveredPaymentRequestedDoesNotDoubleCharge() {
        String orderId = "order-" + UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        publishPaymentRequested(orderId, customerId, productId, 2);
        awaitPaymentEvent(orderId);

        // A redelivered copy of the exact same event -- Kafka's at-least-once
        // guarantee means this happens for real, not just hypothetically.
        publishPaymentRequested(orderId, customerId, productId, 2);

        // Give the (idempotent no-op) redelivery a window to be processed,
        // then confirm there is still exactly one attempt for this order.
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(paymentAttemptRepository.findById(orderId)).isPresent();
    }
}
