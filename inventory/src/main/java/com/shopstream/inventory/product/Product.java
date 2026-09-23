package com.shopstream.inventory.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "stock_quantity", nullable = false)
    private int stockQuantity;

    /**
     * Optimistic-locking version. Every UPDATE Hibernate issues includes
     * "WHERE id = ? AND version = ?"; if two requests read the same row and
     * both try to write, the second one's UPDATE matches zero rows and
     * Hibernate throws ObjectOptimisticLockingFailureException instead of
     * silently overwriting the first write. That's what makes concurrent
     * stock reservations safe without taking a DB row lock.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Product(UUID id, String sku, String name, String description, BigDecimal price, int stockQuantity,
            Instant createdAt) {
        this.id = id;
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.createdAt = createdAt;
    }

    public static Product create(String sku, String name, String description, BigDecimal price, int initialStock) {
        return new Product(UUID.randomUUID(), sku, name, description, price, initialStock, Instant.now());
    }
}
