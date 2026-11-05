package com.shopstream.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shopstream.gateway.support.AbstractRedisIT;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import reactor.core.publisher.Flux;

/** The Lua token bucket, exercised against a real Redis. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class TokenBucketRateLimiterIT extends AbstractRedisIT {

    @Autowired
    private TokenBucketRateLimiter limiter;

    private static String freshKey() {
        return "rl:test:" + UUID.randomUUID();
    }

    private long allowedInConcurrentBurst(String key, RateLimitProperties.Policy policy, int requests) {
        List<TokenBucketRateLimiter.Decision> decisions = Flux.range(0, requests)
                .flatMap(i -> limiter.tryConsume(key, policy), requests) // all in flight at once
                .collectList()
                .block(Duration.ofSeconds(10));
        return decisions.stream().filter(TokenBucketRateLimiter.Decision::allowed).count();
    }

    @Test
    void aConcurrentBurstSucceedsExactlyCapacityTimes() {
        // Slow refill, so no meaningful top-up happens during the (millisecond-long) burst.
        var policy = new RateLimitProperties.Policy(10, 0.5);

        long allowed = allowedInConcurrentBurst(freshKey(), policy, 100);

        // The atomicity check: 100 simultaneous requests, never more than 10 admitted.
        assertThat(allowed).isEqualTo(10);
    }

    @Test
    void remainingTokensCountDownAndTheRejectionCarriesARetryDelay() {
        var policy = new RateLimitProperties.Policy(3, 0.5);
        String key = freshKey();

        var first = limiter.tryConsume(key, policy).block();
        var second = limiter.tryConsume(key, policy).block();
        var third = limiter.tryConsume(key, policy).block();
        var fourth = limiter.tryConsume(key, policy).block();

        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(2);
        assertThat(second.remaining()).isEqualTo(1);
        assertThat(third.allowed()).isTrue();
        assertThat(third.remaining()).isZero();
        assertThat(fourth.allowed()).isFalse();
        // At 0.5 tokens/s a whole token takes ~2s; allow for the few ms already elapsed.
        assertThat(fourth.retryAfterMs()).isBetween(1500L, 2000L);
    }

    @Test
    void theBucketRefillsOverTime() {
        var policy = new RateLimitProperties.Policy(2, 5); // one token every 200 ms
        String key = freshKey();
        limiter.tryConsume(key, policy).block();
        limiter.tryConsume(key, policy).block();
        assertThat(limiter.tryConsume(key, policy).block().allowed()).isFalse();

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                assertThat(limiter.tryConsume(key, policy).block().allowed()).isTrue());
    }

    @Test
    void refillNeverExceedsCapacity() throws InterruptedException {
        var policy = new RateLimitProperties.Policy(3, 20); // 500 ms refills 10 tokens' worth
        String key = freshKey();
        allowedInConcurrentBurst(key, policy, 20); // drain it

        Thread.sleep(500);

        // The bucket holds 3, not 10: after one request exactly 2 remain. (Uncapped it would be 9.)
        var decision = limiter.tryConsume(key, policy).block();
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(2);
    }

    @Test
    void bucketsForDifferentKeysAreIndependent() {
        var policy = new RateLimitProperties.Policy(1, 0.1);
        String a = freshKey();
        String b = freshKey();

        assertThat(limiter.tryConsume(a, policy).block().allowed()).isTrue();
        assertThat(limiter.tryConsume(a, policy).block().allowed()).isFalse();
        assertThat(limiter.tryConsume(b, policy).block().allowed()).isTrue();
    }

    @Test
    void idleBucketsExpireSoRedisMemoryStaysBounded() {
        var policy = new RateLimitProperties.Policy(5, 5); // full again after 1s -> TTL ~2s
        String key = freshKey();
        limiter.tryConsume(key, policy).block();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(REDIS.execInContainer("redis-cli", "EXISTS", key).getStdout().trim()).isEqualTo("0"));
    }
}
