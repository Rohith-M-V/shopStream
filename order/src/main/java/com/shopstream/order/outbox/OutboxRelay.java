package com.shopstream.order.outbox;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The "relay" half of the transactional outbox pattern: a background poller
 * (a "polling publisher"), not a real-time push. It periodically asks "which
 * outbox rows haven't been published yet?", ships each one to Kafka, and marks
 * it published only once Kafka has actually acknowledged it.
 *
 * <p>This is intentionally the simplest correct implementation of the pattern.
 * A production system at real scale would more likely use log-based Change
 * Data Capture (e.g. Debezium reading MySQL's binlog) instead of polling, to
 * avoid hammering the table and to get near-instant delivery instead of
 * waiting up to one poll interval. The trade-off is worth knowing, but the
 * correctness guarantee -- at-least-once delivery, with nothing silently lost
 * -- is identical either way, which is the property that actually matters
 * here.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final String TOPIC = com.shopstream.common.events.Topics.ORDER_EVENTS;
    private static final int BATCH_SIZE = 50;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxRelay(OutboxEventRepository outboxEventRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    public void publishPendingEvents() {
        // Deliberately NOT wrapped in @Transactional: findBy...() and save()
        // below each get their own short transaction automatically from Spring
        // Data JPA's repository proxy. Wrapping this whole method in one
        // transaction would hold a DB connection open across the blocking
        // Kafka call below, for as long as an entire batch takes to send --
        // exactly the kind of long-held transaction that starves a connection
        // pool under load.
        List<OutboxEvent> pending =
                outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(Limit.of(BATCH_SIZE));

        for (OutboxEvent event : pending) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {
        try {
            // The aggregate id (the order id) is the Kafka record key, so every
            // event for the same order lands in the same partition and is
            // delivered to consumers in the order it was written here.
            kafkaTemplate.send(TOPIC, event.getAggregateId(), event.getPayload())
                    .get(5, TimeUnit.SECONDS);
            event.markPublished();
            outboxEventRepository.save(event);
        } catch (Exception e) {
            // Leave it unpublished -- the next poll cycle will retry it. This is
            // what makes the relay "at-least-once": a transient Kafka outage
            // just delays delivery, it never loses the event.
            log.warn("Failed to publish outbox event {} (aggregateId={}); will retry next poll",
                    event.getId(), event.getAggregateId(), e);
        }
    }
}
