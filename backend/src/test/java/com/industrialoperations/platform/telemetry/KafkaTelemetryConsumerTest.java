package com.industrialoperations.platform.telemetry;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.industrialoperations.platform.common.Measurements;

public class KafkaTelemetryConsumerTest {

    private KafkaTelemetryConsumer consumer;
    private TelemetryEventValidator telemetryEventValidator;
    private TelemetryService telemetryService;

    @BeforeEach
    void setUp() {
        telemetryEventValidator = mock(TelemetryEventValidator.class);
        telemetryService = mock(TelemetryService.class);
        consumer = new KafkaTelemetryConsumer(telemetryEventValidator, telemetryService);
    }

    @Test
    void shouldValidateAndRecordTelemetryOnValidEvent() {
        UUID machineId = UUID.randomUUID();

        TelemetryReceivedEvent telemetryReceivedEvent = TelemetryReceivedEvent.of("sourceMessageId", "sensorId",
                machineId, Instant.now(), Instant.now(), new BigDecimal(20.2), new BigDecimal(100.5));

        consumer.consume(telemetryReceivedEvent);

        verify(telemetryEventValidator).validate(telemetryReceivedEvent);

        RecordTelemetryCommand expectedCommand = new RecordTelemetryCommand(
                telemetryReceivedEvent.eventId(),
                telemetryReceivedEvent.source().sourceMessageId(),
                telemetryReceivedEvent.source().sensorId(),
                telemetryReceivedEvent.source().machineId(),
                telemetryReceivedEvent.occurredAt(),
                telemetryReceivedEvent.receivedAt(),
                new Measurements(
                        telemetryReceivedEvent.payload().temperature(),
                        telemetryReceivedEvent.payload().vibration()));

        verify(telemetryService).recordTelemetry(expectedCommand);
    }

    @Test
    void shouldNotRecordTelemetryWhenValidationFails() {
        UUID machineId = UUID.randomUUID();

        TelemetryReceivedEvent telemetryReceivedEvent = TelemetryReceivedEvent.of("sourceMessageId", "sensorId",
                machineId, Instant.now(), Instant.now(), new BigDecimal(20.2), new BigDecimal(100.5));

        doThrow(new InvalidTelemetryEventException("Invalid event")).when(telemetryEventValidator)
                .validate(telemetryReceivedEvent);

        assertThatThrownBy(() -> consumer.consume(telemetryReceivedEvent))
                .isInstanceOf(InvalidTelemetryEventException.class);

        verifyNoInteractions(telemetryService);
    }
}
