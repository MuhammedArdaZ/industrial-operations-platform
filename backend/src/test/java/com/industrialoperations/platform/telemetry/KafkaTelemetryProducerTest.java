package com.industrialoperations.platform.telemetry;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaTelemetryProducerTest {

    private static final String TOPIC = "telemetry-events";
    private static final Duration PUBLISH_TIMEOUT = Duration.ofMillis(50);

    private KafkaTemplate<String, TelemetryReceivedEvent> kafkaTemplate;
    private KafkaTelemetryProducer producer;

    @BeforeEach
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);

        producer = new KafkaTelemetryProducer(kafkaTemplate, TOPIC, PUBLISH_TIMEOUT);
    }

    @Test
    @DisplayName("Should publish telemetry event using machineId as partition key")
    void shouldPublishTelemetryEventWithMachineIdAsPartitionKey() {
        UUID machineId = UUID.randomUUID();

        TelemetryReceivedEvent telemetryReceivedEvent = TelemetryReceivedEvent.of("sourceMessageId", "sensorId",
                machineId, Instant.now(), Instant.now(), new BigDecimal(20.2), new BigDecimal(100.5));

        when(kafkaTemplate.send(TOPIC, telemetryReceivedEvent.source().machineId().toString(),
                telemetryReceivedEvent))
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

        when(kafkaTemplate.send(TOPIC, telemetryReceivedEvent.source().machineId().toString(),
                telemetryReceivedEvent))
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

        when(kafkaTemplate.send(TOPIC, telemetryReceivedEvent.source().machineId().toString(),
                telemetryReceivedEvent))
                .thenReturn(new CompletableFuture<>());

        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> producer.publish(telemetryReceivedEvent))
                .isInstanceOf(TelemetryPublishException.class)
                .cause().isInstanceOf(InterruptedException.class);

        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    @DisplayName("Should wrap timeout in TelemetryPublishException when Kafka ack does not arrive in time")
    void shouldCatchTimeoutAndThrowError() {
        UUID machineId = UUID.randomUUID();

        TelemetryReceivedEvent telemetryReceivedEvent = TelemetryReceivedEvent.of("sourceMessageId", "sensorId",
                machineId, Instant.now(), Instant.now(), new BigDecimal(20.2), new BigDecimal(100.5));

        when(kafkaTemplate.send(TOPIC, telemetryReceivedEvent.source().machineId().toString(),
                telemetryReceivedEvent))
                .thenReturn(new CompletableFuture<>());

        assertTimeout(Duration.ofSeconds(2),
                () -> assertThatThrownBy(() -> producer.publish(telemetryReceivedEvent))
                        .isInstanceOf(TelemetryPublishException.class)
                        .cause().isInstanceOf(TimeoutException.class));
    }

    @Test
    @DisplayName("Should complete publish when Kafka ack arrives before the timeout")
    void shouldSucceedWhenKafkaAckArrivesWithinTimeout() {
        UUID machineId = UUID.randomUUID();

        TelemetryReceivedEvent telemetryReceivedEvent = TelemetryReceivedEvent.of("sourceMessageId", "sensorId",
                machineId, Instant.now(), Instant.now(), new BigDecimal(20.2), new BigDecimal(100.5));

        Executor delay = CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS);

        when(kafkaTemplate.send(TOPIC, telemetryReceivedEvent.source().machineId().toString(),
                telemetryReceivedEvent))
                .thenReturn(CompletableFuture.supplyAsync(() -> null, delay));

        KafkaTelemetryProducer delayedProducer = new KafkaTelemetryProducer(kafkaTemplate, TOPIC, Duration.ofMillis(500));

        assertThatCode(() -> delayedProducer.publish(telemetryReceivedEvent)).doesNotThrowAnyException();
    }
}
