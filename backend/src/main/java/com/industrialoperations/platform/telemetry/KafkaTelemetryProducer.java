package com.industrialoperations.platform.telemetry;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

        try {
            kafkaTemplate.send(topic, partitionKey, telemetryReceivedEvent).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TelemetryPublishException("Kafka message sending interrupted [sourceMessageId=%s]"
                    .formatted(telemetryReceivedEvent.source().sourceMessageId()),
                    e);
        } catch (ExecutionException e) {
            throw new TelemetryPublishException("Kafka rejected the publish [sourceMessageId=%s]"
                    .formatted(telemetryReceivedEvent.source().sourceMessageId()),
                    e.getCause());
        } catch (TimeoutException e) {
            throw new TelemetryPublishException("Timed out waiting for Kafka ack [sourceMessageId=%s]"
                    .formatted(telemetryReceivedEvent.source().sourceMessageId()),
                    e);
        }
    }
}
