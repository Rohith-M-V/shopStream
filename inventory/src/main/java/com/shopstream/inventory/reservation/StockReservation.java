package com.shopstream.inventory.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row per stock reservation attempt. Its id is supplied by the CALLER
 * (eventually: the order id, once the order service exists), not generated
 * here -- that is what makes {@code reserve} idempotent. If a caller retries
 * the exact same reservation id (because a network call timed out, or later,
 * because Kafka redelivered a message), we can look the id up here and
 * recognise "I already did this" instead of decrementing stock twice.
 */
@Entity
@Table(name = "stock_reservations")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockReservation {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "product_id", nullable = false)
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID productId;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private StockReservation(String id, UUID productId, int quantity, ReservationStatus status, Instant createdAt) {
        this.id = id;
        this.productId = productId;
        this.quantity = quantity;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static StockReservation create(String id, UUID productId, int quantity) {
        return new StockReservation(id, productId, quantity, ReservationStatus.RESERVED, Instant.now());
    }
}
