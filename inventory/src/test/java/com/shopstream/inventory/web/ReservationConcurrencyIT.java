package com.shopstream.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.shopstream.common.web.HeaderNames;
import com.shopstream.inventory.dto.CreateProductRequest;
import com.shopstream.inventory.dto.ProductResponse;
import com.shopstream.inventory.dto.ReserveStockRequest;
import com.shopstream.inventory.support.AbstractIntegrationTest;
import com.shopstream.inventory.support.TestJwtSupport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * This is the test that actually earns the resume line "prevented overselling
 * under concurrent load" -- it fires more simultaneous reservation requests
 * than there is stock to satisfy, against a real MySQL instance, and checks
 * that exactly as many succeed as there was stock, never more.
 */
class ReservationConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void concurrentReservationsNeverOversellStock() throws Exception {
        int initialStock = 10;
        int concurrentRequests = 30; // deliberately more requests than available stock

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth(TestJwtSupport.token("ADMIN"));
        var createRequest = new CreateProductRequest(
                "SKU-CONCURRENT-1", "Limited Drop", null, new BigDecimal("50.00"), initialStock);
        ProductResponse product = restTemplate.exchange("/api/products", HttpMethod.POST,
                new HttpEntity<>(createRequest, adminHeaders), ProductResponse.class).getBody();

        String customerToken = TestJwtSupport.token("CUSTOMER");
        ExecutorService pool = Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch start = new CountDownLatch(1);

        List<Callable<HttpStatus>> tasks = new ArrayList<>();
        for (int i = 0; i < concurrentRequests; i++) {
            tasks.add(() -> {
                start.await(); // every thread blocks here until released together, below
                HttpHeaders headers = new HttpHeaders();
                headers.setBearerAuth(customerToken);
                headers.set(HeaderNames.IDEMPOTENCY_KEY, "concurrent-" + UUID.randomUUID());
                var response = restTemplate.exchange(
                        "/api/products/" + product.id() + "/reserve", HttpMethod.POST,
                        new HttpEntity<>(new ReserveStockRequest(1), headers), String.class);
                return (HttpStatus) response.getStatusCode();
            });
        }

        List<Future<HttpStatus>> futures = new ArrayList<>();
        for (Callable<HttpStatus> task : tasks) {
            futures.add(pool.submit(task));
        }
        start.countDown(); // release all 30 threads at once

        long succeeded = 0;
        long rejected = 0;
        for (Future<HttpStatus> future : futures) {
            HttpStatus status = future.get(30, TimeUnit.SECONDS);
            if (status == HttpStatus.OK) {
                succeeded++;
            } else {
                rejected++;
            }
        }
        pool.shutdown();

        // The actual proof: exactly as many reservations succeed as there was
        // stock -- not more (overselling) and, thanks to the retry loop, not
        // fewer either (a naive single-attempt approach would reject some
        // requests to real available stock just because of lock contention).
        assertThat(succeeded).isEqualTo(initialStock);
        assertThat(rejected).isEqualTo(concurrentRequests - initialStock);

        ProductResponse finalState =
                restTemplate.getForEntity("/api/products/" + product.id(), ProductResponse.class).getBody();
        assertThat(finalState.stockQuantity()).isEqualTo(0);
    }
}
