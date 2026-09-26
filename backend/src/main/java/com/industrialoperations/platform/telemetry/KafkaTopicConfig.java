package com.industrialoperations.platform.telemetry;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    private final String telemetryTopic;

    public KafkaTopicConfig(@Value("${platform.kafka.telemetry-topic}") String telemetryTopic) {
        this.telemetryTopic = telemetryTopic;
    }

    @Bean
    public NewTopic telemetryEventsTopic() {
        return TopicBuilder.name(telemetryTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
