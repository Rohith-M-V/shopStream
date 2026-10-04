package com.shopstream.payment.payment;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Same idempotency-ledger pattern as inventory's StockReservation: the id IS
 * the order id, not a server-generated one. A redelivered PaymentRequested
 * event for an order that already has a row here is recognised as "already
 * processed" instead of charging twice -- the single most important
 * guarantee a payment system can make.
 */
@Entity
@Table(name = "payment_attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAttempt {

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
    private PaymentStatus status;

    @Column(length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private PaymentAttempt(String id, UUID customerId, UUID productId, int quantity, PaymentStatus status,
            String reason, Instant createdAt) {
        this.id = id;
        this.customerId = customerId;
        this.productId = productId;
        this.quantity = quantity;
        this.status = status;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public static PaymentAttempt succeeded(String orderId, UUID customerId, UUID productId, int quantity) {
        return new PaymentAttempt(orderId, customerId, productId, quantity, PaymentStatus.SUCCEEDED, null,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    public static PaymentAttempt failed(String orderId, UUID customerId, UUID productId, int quantity,
            String reason) {
        return new PaymentAttempt(orderId, customerId, productId, quantity, PaymentStatus.FAILED, reason,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
}
