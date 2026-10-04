package com.shopstream.payment.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * The third copy of the exact same transactional outbox pattern used by
 * order (Phase 3) and inventory (Phase 4). By now that repetition IS the
 * lesson: this is a general-purpose pattern for any service that both writes
 * to its own database and needs to reliably tell another service about it --
 * not a one-off trick that belongs to whichever service happened to need it
 * first.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    private OutboxEvent(UUID id, String aggregateType, String aggregateId, String eventType, String payload,
            Instant createdAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public static OutboxEvent create(String aggregateType, String aggregateId, String eventType, String payload) {
        return new OutboxEvent(UUID.randomUUID(), aggregateType, aggregateId, eventType, payload,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }
}
