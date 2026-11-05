package com.shopstream.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/**
 * Unit tests for the filter's decisions (which bucket, what response) with the
 * limiter mocked. The Lua script itself is covered against real Redis in
 * TokenBucketRateLimiterIT.
 */
class RateLimitFilterTest {

    private static final RateLimitProperties.Policy USER = new RateLimitProperties.Policy(50, 25);
    private static final RateLimitProperties.Policy ANON = new RateLimitProperties.Policy(30, 10);
    private static final RateLimitProperties.Policy AUTH = new RateLimitProperties.Policy(5, 0.1);

    private TokenBucketRateLimiter limiter;
    private SimpleMeterRegistry meters;
    private final AtomicBoolean chainCalled = new AtomicBoolean();
    private final GatewayFilterChain chain = exchange -> {
        chainCalled.set(true);
        return Mono.empty();
    };

    @BeforeEach
    void setUp() {
        limiter = mock(TokenBucketRateLimiter.class);
        meters = new SimpleMeterRegistry();
        when(limiter.tryConsume(anyString(), any()))
                .thenReturn(Mono.just(new TokenBucketRateLimiter.Decision(true, 4, 0)));
    }

    private RateLimitFilter filter(boolean trustForwardedFor) {
        return new RateLimitFilter(limiter,
                new RateLimitProperties(true, trustForwardedFor, 150, USER, ANON, AUTH), meters);
    }

    private static MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> builder) {
        return MockServerWebExchange.from(builder.remoteAddress(new InetSocketAddress("10.0.0.5", 5555)).build());
    }

    @Test
    void anonymousRequestIsChargedToTheClientIpWithTheAnonymousPolicy() {
        var exchange = exchange(MockServerHttpRequest.get("/api/products/1"));

        filter(false).filter(exchange, chain).block();

        verify(limiter).tryConsume("rl:anon:10.0.0.5", ANON);
        assertThat(chainCalled).isTrue();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("4");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Burst-Capacity")).isEqualTo("30");
    }

    @Test
    void authenticatedRequestIsChargedToTheJwtSubject() {
        var jwt = Jwt.withTokenValue("t").header("alg", "none").subject("user-42").build();
        var exchange = exchange(MockServerHttpRequest.get("/api/orders"));
        var authentication = new JwtAuthenticationToken(jwt);
        authentication.setAuthenticated(true);
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo("user-42");

        filter(false).filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                .block();

        verify(limiter).tryConsume("rl:user:user-42", USER);
        assertThat(chainCalled).isTrue();
    }

    @Test
    void loginAndRegisterUseTheTightAuthPolicyKeyedByIp() {
        filter(false).filter(exchange(MockServerHttpRequest.post("/api/auth/login")), chain).block();
        filter(false).filter(exchange(MockServerHttpRequest.post("/api/auth/register")), chain).block();

        verify(limiter, org.mockito.Mockito.times(2)).tryConsume("rl:auth:10.0.0.5", AUTH);
    }

    @Test
    void otherAuthEndpointsAreNotTreatedAsLoginAttempts() {
        filter(false).filter(exchange(MockServerHttpRequest.get("/api/auth/login")), chain).block();

        verify(limiter).tryConsume(eq("rl:anon:10.0.0.5"), eq(ANON));
    }

    @Test
    void overLimitRequestGets429WithRetryAfterAndNeverReachesTheRoute() {
        when(limiter.tryConsume(anyString(), any()))
                .thenReturn(Mono.just(new TokenBucketRateLimiter.Decision(false, 0, 1500)));
        var exchange = exchange(MockServerHttpRequest.get("/api/products/1"));

        filter(false).filter(exchange, chain).block();

        assertThat(chainCalled).isFalse();
        var response = exchange.getResponse();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("2"); // 1500 ms rounds up
        assertThat(response.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(response.getBodyAsString().block()).contains("\"status\":429").contains("TOO_MANY_REQUESTS");
        assertThat(meters.counter("gateway.ratelimit.requests", "policy", "anonymous", "result", "limited").count())
                .isEqualTo(1.0);
    }

    @Test
    void retryAfterIsAtLeastOneSecond() {
        when(limiter.tryConsume(anyString(), any()))
                .thenReturn(Mono.just(new TokenBucketRateLimiter.Decision(false, 0, 10)));
        var exchange = exchange(MockServerHttpRequest.get("/api/products/1"));

        filter(false).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    @Test
    void failsOpenWhenTheLimiterErrors() {
        when(limiter.tryConsume(anyString(), any())).thenReturn(Mono.error(new IllegalStateException("redis down")));
        var exchange = exchange(MockServerHttpRequest.get("/api/products/1"));

        filter(false).filter(exchange, chain).block();

        assertThat(chainCalled).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(meters.counter("gateway.ratelimit.requests", "policy", "anonymous", "result", "error").count())
                .isEqualTo(1.0);
    }

    @Test
    void forwardedForIsIgnoredUnlessExplicitlyTrusted() {
        var request = MockServerHttpRequest.get("/api/products/1").header("X-Forwarded-For", "1.1.1.1");

        filter(false).filter(exchange(request), chain).block();

        verify(limiter).tryConsume("rl:anon:10.0.0.5", ANON);
    }

    @Test
    void whenTrustedTheRightmostForwardedForEntryIsUsed() {
        // Leftmost entries are client-supplied and spoofable; the last one is what our proxy appended.
        var request = MockServerHttpRequest.get("/api/products/1")
                .header("X-Forwarded-For", "6.6.6.6, 203.0.113.9");

        filter(true).filter(exchange(request), chain).block();

        verify(limiter).tryConsume("rl:anon:203.0.113.9", ANON);
    }

    @Test
    void invalidPolicyValuesAreRejectedAtStartup() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RateLimitProperties.Policy(0, 1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RateLimitProperties.Policy(10, 0));
    }
}
