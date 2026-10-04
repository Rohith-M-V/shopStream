package com.shopstream.payment.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shopstream.common.events.EventTypes;
import com.shopstream.common.events.Topics;
import com.shopstream.payment.dto.PaymentResponse;
import com.shopstream.payment.support.AbstractIntegrationTest;
import com.shopstream.payment.support.TestJwtSupport;
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

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentControllerIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private String triggerPaymentFor(UUID customerId, int quantity) {
        String orderId = "order-" + UUID.randomUUID();
        UUID productId = UUID.randomUUID();
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
        return orderId;
    }

    @Test
    void ownerCanReadTheirPaymentStatus() {
        UUID customerId = UUID.randomUUID();
        String orderId = triggerPaymentFor(customerId, 2);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.token(customerId, "CUSTOMER"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var response = restTemplate.exchange("/api/payments/" + orderId, HttpMethod.GET,
                    new HttpEntity<>(headers), PaymentResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().status()).isEqualTo("SUCCEEDED");
        });
    }

    @Test
    void aDifferentCustomerCannotReadSomeoneElsesPaymentStatus() {
        UUID ownerId = UUID.randomUUID();
        String orderId = triggerPaymentFor(ownerId, 2);

        HttpHeaders ownerHeaders = new HttpHeaders();
        ownerHeaders.setBearerAuth(TestJwtSupport.token(ownerId, "CUSTOMER"));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(restTemplate.exchange("/api/payments/" + orderId, HttpMethod.GET,
                        new HttpEntity<>(ownerHeaders), PaymentResponse.class).getStatusCode())
                        .isEqualTo(HttpStatus.OK));

        HttpHeaders otherHeaders = new HttpHeaders();
        otherHeaders.setBearerAuth(TestJwtSupport.token(UUID.randomUUID(), "CUSTOMER"));
        var response = restTemplate.exchange("/api/payments/" + orderId, HttpMethod.GET,
                new HttpEntity<>(otherHeaders), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void requestingWithoutATokenIsRejected() {
        var response = restTemplate.getForEntity("/api/payments/order-does-not-matter", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
