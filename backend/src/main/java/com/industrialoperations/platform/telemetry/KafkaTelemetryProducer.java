package com.industrialoperations.platform.telemetry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaTelemetryProducer implements TelemetryEventPublisher {
    private final String topic;
    private final KafkaTemplate<String, TelemetryReceivedEvent> kafkaTemplate;

    public KafkaTelemetryProducer(
            KafkaTemplate<String, TelemetryReceivedEvent> kafkaTemplate,
            @Value("${platform.kafka.telemetry-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(TelemetryReceivedEvent telemetryReceivedEvent) {
        String partitionKey = telemetryReceivedEvent.source().machineId().toString();
        kafkaTemplate.send(topic, partitionKey, telemetryReceivedEvent);
    }
}
