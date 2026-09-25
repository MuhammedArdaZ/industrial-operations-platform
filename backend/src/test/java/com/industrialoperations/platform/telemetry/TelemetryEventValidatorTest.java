package com.industrialoperations.platform.telemetry;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.industrialoperations.platform.telemetry.TelemetryReceivedEvent.Payload;
import com.industrialoperations.platform.telemetry.TelemetryReceivedEvent.Source;

class TelemetryEventValidatorTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MACHINE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-16T12:00:00Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-09-16T12:00:01Z");

    private final TelemetryEventValidator validator = new TelemetryEventValidator();

    @Test
    void shouldAcceptValidEvent() {
        assertThatCode(() -> validator.validate(validEvent())).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectNullEvent() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(InvalidTelemetryEventException.class);
    }

    @Test
    void shouldRejectUnexpectedEventType() {
        TelemetryReceivedEvent event = event("TelemetryRejected",
                TelemetryReceivedEvent.CURRENT_SCHEMA_VERSION, validSource(), validPayload());

        assertThatThrownBy(() -> validator.validate(event))
                .isInstanceOf(InvalidTelemetryEventException.class)
                .hasMessageContaining("eventType");
    }

    @Test
    void shouldRejectUnsupportedSchemaVersion() {
        TelemetryReceivedEvent event = event(TelemetryReceivedEvent.EVENT_TYPE, 99,
                validSource(), validPayload());

        assertThatThrownBy(() -> validator.validate(event))
                .isInstanceOf(InvalidTelemetryEventException.class)
                .hasMessageContaining("schemaVersion 99");
    }

    @Test
    void shouldRejectMissingSource() {
        TelemetryReceivedEvent event = event(TelemetryReceivedEvent.EVENT_TYPE,
                TelemetryReceivedEvent.CURRENT_SCHEMA_VERSION, null, validPayload());

        assertThatThrownBy(() -> validator.validate(event))
                .isInstanceOf(InvalidTelemetryEventException.class)
                .hasMessageContaining("source is missing");
    }

    @Test
    void shouldRejectBlankSensorId() {
        TelemetryReceivedEvent event = event(TelemetryReceivedEvent.EVENT_TYPE,
                TelemetryReceivedEvent.CURRENT_SCHEMA_VERSION,
                new Source("msg-1", "  ", MACHINE_ID), validPayload());

        assertThatThrownBy(() -> validator.validate(event))
                .isInstanceOf(InvalidTelemetryEventException.class)
                .hasMessageContaining("sensorId is missing");
    }

    @Test
    void shouldRejectMissingMachineId() {
        TelemetryReceivedEvent event = event(TelemetryReceivedEvent.EVENT_TYPE,
                TelemetryReceivedEvent.CURRENT_SCHEMA_VERSION,
                new Source("msg-1", "sensor-1", null), validPayload());

        assertThatThrownBy(() -> validator.validate(event))
                .isInstanceOf(InvalidTelemetryEventException.class)
                .hasMessageContaining("machineId is missing");
    }

    @Test
    void shouldRejectMissingMeasurement() {
        TelemetryReceivedEvent event = event(TelemetryReceivedEvent.EVENT_TYPE,
                TelemetryReceivedEvent.CURRENT_SCHEMA_VERSION, validSource(),
                new Payload(new BigDecimal("85.5"), null));

        assertThatThrownBy(() -> validator.validate(event))
                .isInstanceOf(InvalidTelemetryEventException.class)
                .hasMessageContaining("vibration is missing");
    }

    @Test
    void shouldCarryCorrelationMetadataInRejectionMessage() {
        TelemetryReceivedEvent event = event(TelemetryReceivedEvent.EVENT_TYPE, 99,
                validSource(), validPayload());

        assertThatThrownBy(() -> validator.validate(event))
                .hasMessageContaining(EVENT_ID.toString())
                .hasMessageContaining("msg-1");
    }

    private TelemetryReceivedEvent validEvent() {
        return event(TelemetryReceivedEvent.EVENT_TYPE, TelemetryReceivedEvent.CURRENT_SCHEMA_VERSION,
                validSource(), validPayload());
    }

    private TelemetryReceivedEvent event(String eventType, int schemaVersion, Source source, Payload payload) {
        return new TelemetryReceivedEvent(EVENT_ID, eventType, schemaVersion, OCCURRED_AT, RECEIVED_AT,
                source, payload);
    }

    private Source validSource() {
        return new Source("msg-1", "sensor-1", MACHINE_ID);
    }

    private Payload validPayload() {
        return new Payload(new BigDecimal("85.5"), new BigDecimal("0.04"));
    }
}
