package com.shopstream.order.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The order's id IS the caller-supplied Idempotency-Key, not a server-generated
 * UUID -- the same pattern inventory's StockReservation uses. This is what makes
 * "create this order" safe to retry: replaying the exact same request finds the
 * existing row instead of creating a duplicate. It also sets up Phase 4 neatly:
 * this same id is what gets passed to inventory's /reserve endpoint as ITS
 * idempotency key, so one id ties the whole saga together end to end.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "customer_id", nullable = false)
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID customerId;

    @Column(name = "product_id", nullable = false)
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID productId;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Order(String id, UUID customerId, UUID productId, int quantity, OrderStatus status, Instant createdAt) {
        this.id = id;
        this.customerId = customerId;
        this.productId = productId;
        this.quantity = quantity;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static Order create(String id, UUID customerId, UUID productId, int quantity) {
        return new Order(id, customerId, productId, quantity, OrderStatus.PENDING, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
}
