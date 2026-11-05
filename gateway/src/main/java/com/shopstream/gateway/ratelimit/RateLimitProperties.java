package com.shopstream.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Rate-limit settings, bound from {@code app.rate-limit.*}.
 *
 * <p>Three buckets, picked per request in {@link RateLimitFilter}:
 * <ul>
 *   <li>{@code user}: logged-in callers, keyed by JWT subject</li>
 *   <li>{@code anonymous}: callers without a valid token on public routes, keyed by IP</li>
 *   <li>{@code auth}: POST /api/auth/login and /register, keyed by IP; much tighter,
 *       because these are the brute-force / credential-stuffing targets</li>
 * </ul>
 *
 * @param trustForwardedFor read the client IP from X-Forwarded-For. Leave false unless the
 *                          gateway sits behind a proxy you control: otherwise any client can
 *                          set the header and pick its own bucket.
 * @param redisTimeoutMs    upper bound on the limiter's Redis round trip; past it the request
 *                          is let through (fail-open)
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("false") boolean trustForwardedFor,
        @DefaultValue("150") long redisTimeoutMs,
        @DefaultValue Policy user,
        @DefaultValue Policy anonymous,
        @DefaultValue Policy auth) {

    /**
     * @param capacity        bucket size: the largest burst allowed
     * @param refillPerSecond sustained rate: tokens added per second (may be fractional,
     *                        e.g. 0.1 = one request every 10 seconds)
     */
    public record Policy(
            @DefaultValue("20") int capacity,
            @DefaultValue("5") double refillPerSecond) {

        public Policy {
            if (capacity <= 0) {
                throw new IllegalArgumentException("rate-limit capacity must be > 0, got " + capacity);
            }
            if (refillPerSecond <= 0) {
                throw new IllegalArgumentException("rate-limit refillPerSecond must be > 0, got " + refillPerSecond);
            }
        }
    }
}
