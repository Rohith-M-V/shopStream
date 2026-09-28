package com.shopstream.order.config;

import com.shopstream.common.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declaring the topic as a bean (rather than relying on the broker's
 * auto.create.topics.enable) means Spring Kafka's KafkaAdmin provisions it
 * explicitly at startup, with a partition count and replication factor we
 * control -- worth doing deliberately rather than letting whatever default
 * the broker happens to have decide it.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name(Topics.ORDER_EVENTS)
                .partitions(3)
                .replicas(1) // single-broker local/dev cluster; bump this in a real deployment
                .build();
    }
}
