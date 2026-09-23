package com.shopstream.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.shopstream.common.web.HeaderNames;
import com.shopstream.inventory.dto.CreateProductRequest;
import com.shopstream.inventory.dto.ProductResponse;
import com.shopstream.inventory.dto.ReservationResponse;
import com.shopstream.inventory.dto.ReserveStockRequest;
import com.shopstream.inventory.support.AbstractIntegrationTest;
import com.shopstream.inventory.support.TestJwtSupport;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

class ProductControllerIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private HttpHeaders authHeaders(String role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.token(role));
        return headers;
    }

    private ProductResponse createProduct(String sku, int initialStock) {
        var request = new CreateProductRequest(sku, "Test product " + sku, "description", new BigDecimal("9.99"),
                initialStock);
        return restTemplate.exchange("/api/products", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders("ADMIN")), ProductResponse.class).getBody();
    }

    @Test
    void adminCanCreateProductAndAnyoneCanReadItWithoutAToken() {
        ProductResponse created = createProduct("SKU-READ-1", 10);

        var getResponse = restTemplate.getForEntity("/api/products/" + created.id(), ProductResponse.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().stockQuantity()).isEqualTo(10);
        assertThat(getResponse.getBody().sku()).isEqualTo("SKU-READ-1");
    }

    @Test
    void nonAdminCannotCreateProduct() {
        var request = new CreateProductRequest("SKU-DENY-1", "Gadget", null, new BigDecimal("5.00"), 5);

        var response = restTemplate.exchange("/api/products", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders("CUSTOMER")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void creatingWithoutAnyTokenIsRejected() {
        var request = new CreateProductRequest("SKU-DENY-2", "Thing", null, new BigDecimal("1.00"), 1);

        var response = restTemplate.postForEntity("/api/products", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void reserveThenReleaseRestoresStock() {
        ProductResponse product = createProduct("SKU-LIFECYCLE-1", 20);
        String reservationId = "test-reservation-" + UUID.randomUUID();

        HttpHeaders reserveHeaders = authHeaders("CUSTOMER");
        reserveHeaders.set(HeaderNames.IDEMPOTENCY_KEY, reservationId);
        var reserveResponse = restTemplate.exchange("/api/products/" + product.id() + "/reserve", HttpMethod.POST,
                new HttpEntity<>(new ReserveStockRequest(5), reserveHeaders), ReservationResponse.class);

        assertThat(reserveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reserveResponse.getBody().status()).isEqualTo("RESERVED");

        ProductResponse afterReserve =
                restTemplate.getForEntity("/api/products/" + product.id(), ProductResponse.class).getBody();
        assertThat(afterReserve.stockQuantity()).isEqualTo(15);

        restTemplate.postForEntity("/api/reservations/" + reservationId + "/release", null, Void.class);

        ProductResponse afterRelease =
                restTemplate.getForEntity("/api/products/" + product.id(), ProductResponse.class).getBody();
        assertThat(afterRelease.stockQuantity()).isEqualTo(20);
    }

    @Test
    void reservingMoreThanAvailableStockIsRejectedAndStockIsUnchanged() {
        ProductResponse product = createProduct("SKU-INSUFFICIENT-1", 2);

        HttpHeaders reserveHeaders = authHeaders("CUSTOMER");
        reserveHeaders.set(HeaderNames.IDEMPOTENCY_KEY, "test-reservation-" + UUID.randomUUID());
        var response = restTemplate.exchange("/api/products/" + product.id() + "/reserve", HttpMethod.POST,
                new HttpEntity<>(new ReserveStockRequest(10), reserveHeaders), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ProductResponse unchanged =
                restTemplate.getForEntity("/api/products/" + product.id(), ProductResponse.class).getBody();
        assertThat(unchanged.stockQuantity()).isEqualTo(2);
    }

    @Test
    void reservingWithTheSameIdempotencyKeyTwiceOnlyDecrementsStockOnce() {
        ProductResponse product = createProduct("SKU-IDEMPOTENT-1", 10);
        String reservationId = "idempotent-" + UUID.randomUUID();

        HttpHeaders reserveHeaders = authHeaders("CUSTOMER");
        reserveHeaders.set(HeaderNames.IDEMPOTENCY_KEY, reservationId);
        HttpEntity<ReserveStockRequest> entity = new HttpEntity<>(new ReserveStockRequest(4), reserveHeaders);

        var first = restTemplate.exchange("/api/products/" + product.id() + "/reserve", HttpMethod.POST, entity,
                ReservationResponse.class);
        var second = restTemplate.exchange("/api/products/" + product.id() + "/reserve", HttpMethod.POST, entity,
                ReservationResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isEqualTo(first.getBody()); // same reservation replayed, not a new one

        ProductResponse afterBoth =
                restTemplate.getForEntity("/api/products/" + product.id(), ProductResponse.class).getBody();
        assertThat(afterBoth.stockQuantity()).isEqualTo(6); // decremented once, not twice
    }

    @Test
    void cachedProductReadReflectsStockChangesAfterAMutation() {
        // Exercises the cache-aside path end to end: first GET populates the
        // cache; reserve() must evict it; the next GET must show fresh data,
        // not the stale cached value.
        ProductResponse product = createProduct("SKU-CACHE-1", 10);

        restTemplate.getForEntity("/api/products/" + product.id(), ProductResponse.class); // populate cache

        HttpHeaders reserveHeaders = authHeaders("CUSTOMER");
        reserveHeaders.set(HeaderNames.IDEMPOTENCY_KEY, "cache-test-" + UUID.randomUUID());
        restTemplate.exchange("/api/products/" + product.id() + "/reserve", HttpMethod.POST,
                new HttpEntity<>(new ReserveStockRequest(3), reserveHeaders), ReservationResponse.class);

        ProductResponse afterReserve =
                restTemplate.getForEntity("/api/products/" + product.id(), ProductResponse.class).getBody();
        assertThat(afterReserve.stockQuantity()).isEqualTo(7);
    }
}
