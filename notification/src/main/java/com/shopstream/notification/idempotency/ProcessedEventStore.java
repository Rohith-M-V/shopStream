package com.shopstream.notification.idempotency;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The generic idempotent-consumer guard: "have I already handled event X in
 * consumer group G?" answered atomically by the database.
 *
 * <p>Contrast with the earlier phases: payment keys idempotency on the
 * business id (one PaymentAttempt per order) and order keys it on current
 * status (a transition only applies from the exact expected state). Both are
 * natural when the business entity itself records "already done". Here the
 * side effect (a notification) has no such natural key -- an order may
 * legitimately produce more than one notification over time -- so we dedupe on
 * the message identity instead: the envelope's eventId.
 *
 * <p>{@code INSERT IGNORE} (MySQL) makes check-and-claim a single atomic
 * statement. A plain "SELECT then INSERT" would race between two consumer
 * threads, and catching a unique-key violation from {@code save()} would
 * leave the surrounding transaction rollback-only. MANDATORY propagation means
 * this only ever runs inside the caller's transaction, so the claim and the
 * side effect commit or roll back together: if the side effect fails, the
 * claim is undone and the retry topic can process the event again.
 */
@Component
public class ProcessedEventStore {

    private final EntityManager entityManager;

    public ProcessedEventStore(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** @return true if this (group, eventId) was newly claimed, false if already processed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryClaim(String consumerGroup, UUID eventId) {
        int inserted = entityManager.createNativeQuery("""
                        INSERT IGNORE INTO processed_events (consumer_group, event_id, processed_at)
                        VALUES (:consumerGroup, :eventId, CURRENT_TIMESTAMP(6))
                        """)
                .setParameter("consumerGroup", consumerGroup)
                .setParameter("eventId", eventId.toString())
                .executeUpdate();
        return inserted == 1;
    }

    @Transactional(readOnly = true)
    public long count() {
        return ((Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM processed_events")
                .getSingleResult()).longValue();
    }
}
