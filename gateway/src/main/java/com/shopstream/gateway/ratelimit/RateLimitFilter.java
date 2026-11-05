package com.shopstream.gateway.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Applies the Redis token bucket to every request that reaches a route.
 *
 * <p>Runs after Spring Security's web filter, so the JWT has already been verified and
 * its subject is available. Requests that fail authentication are rejected with 401
 * before reaching this filter and don't consume tokens (they are cheap to reject).
 *
 * <p><b>Fails open:</b> if Redis is down, slow or returns garbage, the request is let
 * through and a metric/log line records it. A broken limiter should degrade to
 * "no rate limiting", not take the whole API down with it. The trade-off is that
 * login brute-force protection is off while Redis is unavailable.
 */
@Component
@ConditionalOnProperty(prefix = "app.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final Set<String> AUTH_PATHS = Set.of("/api/auth/login", "/api/auth/register");
    private static final long ERROR_LOG_INTERVAL_MS = 10_000;

    private final TokenBucketRateLimiter limiter;
    private final RateLimitProperties properties;
    private final MeterRegistry meterRegistry;
    private final AtomicLong lastErrorLogMs = new AtomicLong();

    public RateLimitFilter(TokenBucketRateLimiter limiter, RateLimitProperties properties,
            MeterRegistry meterRegistry) {
        this.limiter = limiter;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /** Which bucket a request is charged to. */
    private record Target(String policyName, String key, RateLimitProperties.Policy policy) {
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .filter(auth -> auth instanceof JwtAuthenticationToken && auth.isAuthenticated())
                .map(Authentication::getName)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(subject -> {
                    Target target = resolveTarget(exchange.getRequest(), subject.orElse(null));
                    return limiter.tryConsume(target.key(), target.policy())
                            .map(Optional::of)
                            .onErrorResume(error -> {
                                recordLimiterFailure(target, error);
                                return Mono.just(Optional.<TokenBucketRateLimiter.Decision>empty());
                            })
                            .flatMap(decision -> decision
                                    .map(d -> d.allowed()
                                            ? allow(exchange, chain, target, d)
                                            : reject(exchange, target, d))
                                    // fail-open: the limiter errored, so carry on unlimited
                                    .orElseGet(() -> chain.filter(exchange)));
                });
    }

    private Target resolveTarget(ServerHttpRequest request, String jwtSubject) {
        String ip = clientIp(request);
        boolean authRoute = HttpMethod.POST.equals(request.getMethod())
                && AUTH_PATHS.contains(request.getPath().value());

        if (authRoute) {
            return new Target("auth", "rl:auth:" + ip, properties.auth());
        }
        if (jwtSubject != null) {
            return new Target("user", "rl:user:" + jwtSubject, properties.user());
        }
        return new Target("anonymous", "rl:anon:" + ip, properties.anonymous());
    }

    private String clientIp(ServerHttpRequest request) {
        if (properties.trustForwardedFor()) {
            List<String> forwarded = request.getHeaders().get("X-Forwarded-For");
            if (forwarded != null && !forwarded.isEmpty()) {
                // The rightmost entry is the one our own proxy appended; entries to its
                // left were supplied by the client and can be anything.
                String[] hops = String.join(",", forwarded).split(",");
                String last = hops[hops.length - 1].trim();
                if (!last.isEmpty()) {
                    return last;
                }
            }
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null ? "unknown" : remote.getHostString();
    }

    private Mono<Void> allow(ServerWebExchange exchange, GatewayFilterChain chain, Target target,
            TokenBucketRateLimiter.Decision decision) {
        count(target, "allowed");
        var headers = exchange.getResponse().getHeaders();
        headers.set("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        headers.set("X-RateLimit-Burst-Capacity", String.valueOf(target.policy().capacity()));
        return chain.filter(exchange);
    }

    private Mono<Void> reject(ServerWebExchange exchange, Target target, TokenBucketRateLimiter.Decision decision) {
        count(target, "limited");
        long retryAfterSeconds = Math.max(1, (decision.retryAfterMs() + 999) / 1000);

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set("Retry-After", String.valueOf(retryAfterSeconds));
        response.getHeaders().set("X-RateLimit-Remaining", "0");
        response.getHeaders().set("X-RateLimit-Burst-Capacity", String.valueOf(target.policy().capacity()));

        String body = "{\"status\":429,\"error\":\"TOO_MANY_REQUESTS\",\"message\":\"Rate limit exceeded. Retry after "
                + retryAfterSeconds + " seconds.\"}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    private void recordLimiterFailure(Target target, Throwable error) {
        count(target, "error");
        // Redis being down means every request errors; log at most once per interval.
        long now = System.currentTimeMillis();
        long last = lastErrorLogMs.get();
        if (now - last >= ERROR_LOG_INTERVAL_MS && lastErrorLogMs.compareAndSet(last, now)) {
            log.warn("Rate limiter unavailable, failing open (policy={}): {}", target.policyName(), error.toString());
        }
    }

    private void count(Target target, String result) {
        meterRegistry.counter("gateway.ratelimit.requests", "policy", target.policyName(), "result", result)
                .increment();
    }

    @Override
    public int getOrder() {
        // Before the gateway's own routing/proxy filters (which are 10000 and up).
        return -100;
    }
}
