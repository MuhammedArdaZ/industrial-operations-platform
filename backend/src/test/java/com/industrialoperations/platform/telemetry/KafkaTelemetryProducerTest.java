package com.industrialoperations.platform.telemetry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KafkaTelemetryProducerTest {

    private static final String TOPIC = "telemetry-events";

    private KafkaTemplate<String, TelemetryReceivedEvent> kafkaTemplate;
    private KafkaTelemetryProducer producer;

    @BeforeEach
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);

        producer = new KafkaTelemetryProducer(kafkaTemplate, TOPIC);
    }

    @Test
    @DisplayName("Should publish telemetry event using machineId as partition key")
    void shouldPublishTelemetryEventWithMachineIdAsPartitionKey() {
        UUID machineId = UUID.randomUUID();
        
        TelemetryReceivedEvent telemetryReceivedEvent = TelemetryReceivedEvent.of("sourceMessageId", "sensorId",
                machineId, Instant.now(), Instant.now(), new BigDecimal(20.2), new BigDecimal(100.5));

        producer.publish(telemetryReceivedEvent);

        verify(kafkaTemplate).send(TOPIC, telemetryReceivedEvent.source().machineId().toString(),
                telemetryReceivedEvent);
    }
}
