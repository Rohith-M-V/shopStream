package com.shopstream.gateway.ratelimit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Thin reactive wrapper around the Lua token bucket in {@code scripts/token_bucket.lua}.
 * All the actual rate-limiting logic lives in that script; this class only sends
 * the key and policy and turns the three integers it returns into a decision.
 */
@Component
@ConditionalOnProperty(prefix = "app.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TokenBucketRateLimiter {

    public record Decision(boolean allowed, long remaining, long retryAfterMs) {
    }

    private final ReactiveStringRedisTemplate redis;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> script;
    private final Duration timeout;

    @SuppressWarnings("rawtypes")
    public TokenBucketRateLimiter(ReactiveStringRedisTemplate redis, RedisScript<List> tokenBucketScript,
            RateLimitProperties properties) {
        this.redis = redis;
        this.script = tokenBucketScript;
        this.timeout = Duration.ofMillis(properties.redisTimeoutMs());
    }

    /**
     * Takes one token from the bucket at {@code key}. Errors (Redis down, slow, bad reply)
     * are propagated as an error signal: whether to fail open or closed is the caller's decision.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Mono<Decision> tryConsume(String key, RateLimitProperties.Policy policy) {
        List<String> args = List.of(
                String.valueOf(policy.capacity()),
                String.valueOf(policy.refillPerSecond()),
                "1");

        return redis.execute(script, List.of(key), args)
                // The reactive driver may deliver the script's multi-value reply in
                // pieces, so fold whatever arrives into one flat list of numbers
                // (same approach as Spring Cloud Gateway's own RedisRateLimiter).
                .reduce(new ArrayList<Long>(), (acc, chunk) -> {
                    for (Object value : (List<Object>) chunk) {
                        acc.add(value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value)));
                    }
                    return acc;
                })
                .timeout(timeout)
                .map(results -> {
                    if (results.size() < 3) {
                        throw new IllegalStateException("Unexpected token bucket reply: " + results);
                    }
                    return new Decision(results.get(0) == 1L, results.get(1), results.get(2));
                });
    }
}
