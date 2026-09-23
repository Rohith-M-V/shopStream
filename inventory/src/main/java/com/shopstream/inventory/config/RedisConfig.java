package com.shopstream.inventory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shopstream.inventory.dto.ProductResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, ProductResponse> productRedisTemplate(RedisConnectionFactory connectionFactory) {
        // A plain Jackson2JsonRedisSerializer() uses a default ObjectMapper with no
        // JSR-310 module, which cannot (de)serialize java.time.Instant -- so we build
        // one explicitly, rather than relying on Boot's autoconfigured ObjectMapper bean.
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        var serializer = new Jackson2JsonRedisSerializer<>(objectMapper, ProductResponse.class);

        RedisTemplate<String, ProductResponse> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }
}
