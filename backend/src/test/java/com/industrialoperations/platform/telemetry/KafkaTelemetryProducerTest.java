package com.industrialoperations.platform.telemetry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

        when(kafkaTemplate.send(TOPIC, telemetryReceivedEvent.source().machineId().toString(), telemetryReceivedEvent))
                .thenReturn(CompletableFuture.completedFuture(null));

        producer.publish(telemetryReceivedEvent);

        verify(kafkaTemplate).send(TOPIC, telemetryReceivedEvent.source().machineId().toString(),
                telemetryReceivedEvent);
    }

    @Test
    @DisplayName("Should wrap Kafka publish failure in TelemetryPublishException preserving the original cause")
    void shouldWrapKafkaFailurePreservingCause() {
        UUID machineId = UUID.randomUUID();

        TelemetryReceivedEvent telemetryReceivedEvent = TelemetryReceivedEvent.of("sourceMessageId", "sensorId",
                machineId, Instant.now(), Instant.now(), new BigDecimal(20.2), new BigDecimal(100.5));

        RuntimeException kafkaError = new RuntimeException("Runtime Exception Message");

        when(kafkaTemplate.send(TOPIC, telemetryReceivedEvent.source().machineId().toString(), telemetryReceivedEvent))
                .thenReturn(CompletableFuture.failedFuture(kafkaError));

        assertThatThrownBy(() -> producer.publish(telemetryReceivedEvent))
                .isInstanceOf(TelemetryPublishException.class)
                .cause().isSameAs(kafkaError);
    }

    @Test
    @DisplayName("Should restore the interrupt flag when interrupted while waiting for the Kafka ack")
    void shouldRestoreInterruptFlagWhenInterruptedWhileWaitingForAck() {
        UUID machineId = UUID.randomUUID();

        TelemetryReceivedEvent telemetryReceivedEvent = TelemetryReceivedEvent.of("sourceMessageId", "sensorId",
                machineId, Instant.now(), Instant.now(), new BigDecimal(20.2), new BigDecimal(100.5));

        when(kafkaTemplate.send(TOPIC, telemetryReceivedEvent.source().machineId().toString(), telemetryReceivedEvent))
                .thenReturn(new CompletableFuture<>());

        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> producer.publish(telemetryReceivedEvent))
                .isInstanceOf(TelemetryPublishException.class)
                .cause().isInstanceOf(InterruptedException.class);

        assertThat(Thread.interrupted()).isTrue();
    }
}
