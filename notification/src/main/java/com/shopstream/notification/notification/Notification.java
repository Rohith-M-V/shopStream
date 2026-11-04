package com.shopstream.notification.notification;

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

/** A record of a notification we produced for a customer about one of their orders. */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID eventId;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "customer_id", nullable = false)
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false, length = 150)
    private String subject;

    @Column(nullable = false, length = 500)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Notification(UUID id, UUID eventId, String orderId, UUID customerId, NotificationType type,
            String subject, String body, Instant createdAt) {
        this.id = id;
        this.eventId = eventId;
        this.orderId = orderId;
        this.customerId = customerId;
        this.type = type;
        this.subject = subject;
        this.body = body;
        this.createdAt = createdAt;
    }

    public static Notification create(UUID eventId, String orderId, UUID customerId, NotificationType type,
            String subject, String body) {
        return new Notification(UUID.randomUUID(), eventId, orderId, customerId, type, subject, body,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
}
