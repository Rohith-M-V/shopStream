package com.shopstream.inventory.outbox;

import com.shopstream.common.events.Topics;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Same design as order's OutboxRelay: a scheduled poller, deliberately NOT
 * wrapped in @Transactional for the same reason (findBy...() and save() each
 * get their own short transaction; wrapping the whole poll in one transaction
 * would hold a DB connection open across the blocking Kafka call).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final String TOPIC = Topics.INVENTORY_EVENTS;
    private static final int BATCH_SIZE = 50;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxRelay(OutboxEventRepository outboxEventRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    public void publishPendingEvents() {
        List<OutboxEvent> pending =
                outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(Limit.of(BATCH_SIZE));

        for (OutboxEvent event : pending) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {
        try {
            kafkaTemplate.send(TOPIC, event.getAggregateId(), event.getPayload())
                    .get(5, TimeUnit.SECONDS);
            event.markPublished();
            outboxEventRepository.save(event);
        } catch (Exception e) {
            log.warn("Failed to publish outbox event {} (aggregateId={}); will retry next poll",
                    event.getId(), event.getAggregateId(), e);
        }
    }
}
