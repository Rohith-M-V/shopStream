package com.shopstream.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The public read shape of a product. This is also exactly what gets
 * serialized into Redis for the cache-aside layer -- keeping the cached
 * shape identical to the API response shape means there is no separate
 * "cache DTO" to keep in sync.
 */
public record ProductResponse(
        UUID id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        int stockQuantity,
        Instant createdAt) {
}
