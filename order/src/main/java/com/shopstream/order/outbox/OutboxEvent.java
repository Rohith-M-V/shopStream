package com.shopstream.order.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * The transactional outbox pattern in one table.
 *
 * <p>The problem it solves: an order creation needs to (1) write the order row
 * and (2) publish an OrderCreated event to Kafka. Those are two different
 * systems (MySQL, Kafka) with no shared transaction between them. If you write
 * the order row and THEN call Kafka directly, a crash or network blip between
 * those two steps leaves an order that exists but that nothing downstream ever
 * hears about -- inventory never reserves stock, the order just silently rots.
 *
 * <p>The fix: instead of calling Kafka directly, write a row into THIS table,
 * in the exact same database transaction as the order row. Since it's the same
 * transaction against the same database, both rows commit together or neither
 * does -- there is no window where one exists without the other. A separate
 * process (see {@link OutboxRelay}) then polls this table and publishes each
 * unpublished row to Kafka, marking it published once the broker acknowledges
 * it. If the relay crashes mid-publish, the row is simply still unpublished
 * next time it polls -- nothing is lost, at the cost of a possible duplicate
 * delivery (which is exactly why Kafka consumers need to be idempotent, same
 * as inventory's reservation-by-id design).
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

    // Not @Lob: Hibernate would then expect a CLOB, but the migration creates a
    // plain TEXT column (which JDBC reports as LONGVARCHAR), and schema
    // validation fails on that mismatch. Declaring the JDBC type explicitly
    // keeps the entity and the migration in agreement.
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
        return new OutboxEvent(UUID.randomUUID(), aggregateType, aggregateId, eventType, payload, Instant.now());
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }
}
