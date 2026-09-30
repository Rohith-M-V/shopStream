package com.shopstream.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.shopstream.common.web.HeaderNames;
import com.shopstream.order.dto.CreateOrderRequest;
import com.shopstream.order.dto.OrderResponse;
import com.shopstream.order.support.AbstractIntegrationTest;
import com.shopstream.order.support.TestJwtSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;

// Forces a fresh ApplicationContext (and fresh Testcontainers-backed MySQL/Kafka)
// for the next test class, rather than reusing this one's.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderControllerIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private HttpHeaders authHeaders(UUID customerId, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.token(customerId, "CUSTOMER"));
        headers.set(HeaderNames.IDEMPOTENCY_KEY, idempotencyKey);
        return headers;
    }

    @Test
    void creatingAnOrderReturnsCreatedWithPendingStatus() {
        UUID customerId = UUID.randomUUID();
        String orderId = "order-" + UUID.randomUUID();
        var request = new CreateOrderRequest(UUID.randomUUID(), 3);

        var response = restTemplate.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(customerId, orderId)), OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().id()).isEqualTo(orderId);
        assertThat(response.getBody().customerId()).isEqualTo(customerId);
        assertThat(response.getBody().status()).isEqualTo("PENDING");
    }

    @Test
    void replayingTheSameIdempotencyKeyReturnsTheSameOrderNotADuplicate() {
        UUID customerId = UUID.randomUUID();
        String orderId = "order-" + UUID.randomUUID();
        var request = new CreateOrderRequest(UUID.randomUUID(), 2);
        HttpEntity<CreateOrderRequest> entity = new HttpEntity<>(request, authHeaders(customerId, orderId));

        var first = restTemplate.exchange("/api/orders", HttpMethod.POST, entity, OrderResponse.class);
        var second = restTemplate.exchange("/api/orders", HttpMethod.POST, entity, OrderResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody()).isEqualTo(first.getBody());
    }

    @Test
    void creatingWithoutIdempotencyKeyHeaderIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.token("CUSTOMER"));
        var request = new CreateOrderRequest(UUID.randomUUID(), 1);

        var response = restTemplate.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(request, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void creatingWithoutAnyTokenIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HeaderNames.IDEMPOTENCY_KEY, "order-" + UUID.randomUUID());
        var request = new CreateOrderRequest(UUID.randomUUID(), 1);

        var response = restTemplate.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(request, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ownerCanReadTheirOwnOrder() {
        UUID customerId = UUID.randomUUID();
        String orderId = "order-" + UUID.randomUUID();
        var request = new CreateOrderRequest(UUID.randomUUID(), 1);
        restTemplate.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(customerId, orderId)), OrderResponse.class);

        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.setBearerAuth(TestJwtSupport.token(customerId, "CUSTOMER"));
        var getResponse = restTemplate.exchange("/api/orders/" + orderId, HttpMethod.GET,
                new HttpEntity<>(getHeaders), OrderResponse.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().id()).isEqualTo(orderId);
    }

    @Test
    void aDifferentCustomerCannotReadSomeoneElsesOrder() {
        UUID ownerId = UUID.randomUUID();
        String orderId = "order-" + UUID.randomUUID();
        var request = new CreateOrderRequest(UUID.randomUUID(), 1);
        restTemplate.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(ownerId, orderId)), OrderResponse.class);

        HttpHeaders otherCustomerHeaders = new HttpHeaders();
        otherCustomerHeaders.setBearerAuth(TestJwtSupport.token(UUID.randomUUID(), "CUSTOMER"));
        var response = restTemplate.exchange("/api/orders/" + orderId, HttpMethod.GET,
                new HttpEntity<>(otherCustomerHeaders), String.class);

        // 404, not 403 - never confirm that an order with this id exists at all.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
