package com.shopstream.inventory.cache;

import com.shopstream.inventory.dto.ProductResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Cache-aside for product reads: the application (not Redis) decides when to
 * read from and write to the cache -- Redis never talks to MySQL directly.
 *
 * <p>Two things worth calling out because they're easy to skip in a toy cache:
 * <ul>
 *   <li><b>TTL jitter</b> -- every entry gets a slightly different expiry
 *       (5 minutes + up to 60 random seconds). Without this, a burst of reads
 *       around the same time would all expire at the same instant, sending a
 *       simultaneous spike of requests to MySQL (a "cache stampede").</li>
 *   <li><b>Explicit invalidation on write</b> -- any mutation to a product
 *       (stock reserved, released, or edited) evicts its cache entry rather
 *       than trying to update it in place. Deleting is simpler to reason
 *       about than keeping two copies of the truth in sync, at the cost of
 *       one extra DB read on the next request for that product.</li>
 * </ul>
 */
@Component
public class ProductCacheService {

    private static final String KEY_PREFIX = "product:";
    private static final Duration BASE_TTL = Duration.ofMinutes(5);
    private static final int JITTER_SECONDS = 60;

    private final RedisTemplate<String, ProductResponse> redisTemplate;

    public ProductCacheService(RedisTemplate<String, ProductResponse> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public ProductResponse get(UUID productId) {
        return redisTemplate.opsForValue().get(key(productId));
    }

    public void put(UUID productId, ProductResponse response) {
        Duration ttl = BASE_TTL.plusSeconds(ThreadLocalRandom.current().nextInt(JITTER_SECONDS));
        redisTemplate.opsForValue().set(key(productId), response, ttl);
    }

    public void evict(UUID productId) {
        redisTemplate.delete(key(productId));
    }

    private String key(UUID productId) {
        return KEY_PREFIX + productId;
    }
}
