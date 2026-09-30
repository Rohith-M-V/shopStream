package com.shopstream.inventory.config;

import com.shopstream.common.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Same reasoning as order's KafkaTopicConfig: declaring the topic explicitly
 * rather than relying on broker auto-creation.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic inventoryEventsTopic() {
        return TopicBuilder.name(Topics.INVENTORY_EVENTS)
                .partitions(3)
                .replicas(1) // single-broker local/dev cluster
                .build();
    }
}
