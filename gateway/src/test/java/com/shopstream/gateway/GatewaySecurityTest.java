package com.shopstream.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Runs the gateway on its own, with no other services up. This can't verify a
 * full proxied response (the auth-service URI won't be reachable here), but it
 * proves the thing this phase is actually about: unauthenticated requests to a
 * protected path are rejected before any proxying happens.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewaySecurityTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void protectedRouteWithoutTokenIsRejected() {
        webTestClient.get().uri("/api/orders")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedRouteWithGarbageTokenIsRejected() {
        webTestClient.get().uri("/api/orders")
                .header("Authorization", "Bearer not-a-real-jwt")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void permitAllRouteIsNotBlockedBySecurity() {
        // No auth-service running in this test, so this will fail to proxy --
        // the point is only that it must NOT be 401 (security let it through).
        webTestClient.post().uri("/api/auth/login")
                .exchange()
                .expectStatus().value(status -> {
                    if (status == HttpStatus.UNAUTHORIZED.value()) {
                        throw new AssertionError(
                                "permitAll route was rejected by the security filter chain");
                    }
                });
    }
}
