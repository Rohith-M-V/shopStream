package com.shopstream.payment.config;

import com.shopstream.common.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic paymentEventsTopic() {
        return TopicBuilder.name(Topics.PAYMENT_EVENTS)
                .partitions(3)
                .replicas(1) // single-broker local/dev cluster
                .build();
    }
}
