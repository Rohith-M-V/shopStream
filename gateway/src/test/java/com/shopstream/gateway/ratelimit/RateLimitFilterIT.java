package com.shopstream.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.shopstream.gateway.support.AbstractRedisIT;
import com.shopstream.gateway.support.TestJwtSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Whole gateway + real Redis. No backend services are running, so requests the
 * limiter lets through fail to proxy (5xx); what these tests assert is only
 * "429 or not 429", plus the shape of the 429 itself.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.user.capacity=5",
        "app.rate-limit.user.refill-per-second=0.01",
        "app.rate-limit.anonymous.capacity=3",
        "app.rate-limit.anonymous.refill-per-second=0.01",
        "app.rate-limit.auth.capacity=2",
        "app.rate-limit.auth.refill-per-second=0.01"
})
@AutoConfigureWebTestClient
class RateLimitFilterIT extends AbstractRedisIT {

    @Autowired
    private WebTestClient webTestClient;

    private int get(String uri, String bearerToken) {
        var spec = webTestClient.get().uri(uri);
        if (bearerToken != null) {
            spec = spec.header("Authorization", "Bearer " + bearerToken);
        }
        return spec.exchange().expectBody().returnResult().getStatus().value();
    }

    private int postLogin() {
        return webTestClient.post().uri("/api/auth/login").exchange()
                .expectBody().returnResult().getStatus().value();
    }

    private static final int TOO_MANY = HttpStatus.TOO_MANY_REQUESTS.value();

    @Test
    void anonymousCallerIsLimitedByIp() {
        for (int i = 0; i < 3; i++) {
            assertThat(get("/api/products/1", null)).as("request %d", i + 1).isNotEqualTo(TOO_MANY);
        }

        webTestClient.get().uri("/api/products/1").exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists("Retry-After")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectBody().jsonPath("$.error").isEqualTo("TOO_MANY_REQUESTS");
    }

    @Test
    void eachLoggedInUserHasTheirOwnBucket() {
        String alice = TestJwtSupport.token(UUID.randomUUID(), "CUSTOMER");
        String bob = TestJwtSupport.token(UUID.randomUUID(), "CUSTOMER");

        for (int i = 0; i < 5; i++) {
            assertThat(get("/api/orders", alice)).as("alice request %d", i + 1).isNotEqualTo(TOO_MANY);
        }
        assertThat(get("/api/orders", alice)).as("alice over her limit").isEqualTo(TOO_MANY);

        // Alice exhausting her bucket must not affect Bob.
        assertThat(get("/api/orders", bob)).as("bob unaffected").isNotEqualTo(TOO_MANY);
    }

    @Test
    void loginHasATighterLimitThanTheRestOfTheApi() {
        assertThat(postLogin()).isNotEqualTo(TOO_MANY);
        assertThat(postLogin()).isNotEqualTo(TOO_MANY);
        assertThat(postLogin()).as("third login attempt").isEqualTo(TOO_MANY);
    }

    @Test
    void requestsWithoutAValidTokenAreStillRejectedBySecurityNotTheLimiter() {
        // Protected route, no token: 401 from the security chain, never reaching the limiter.
        for (int i = 0; i < 10; i++) {
            assertThat(get("/api/orders", null)).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        }
    }
}
