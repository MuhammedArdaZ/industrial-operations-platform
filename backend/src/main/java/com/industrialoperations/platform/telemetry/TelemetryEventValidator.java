package com.industrialoperations.platform.telemetry;

import org.springframework.stereotype.Component;

/** Validates the internal event envelope before any database work begins. */
@Component
public class TelemetryEventValidator {

    public void validate(TelemetryReceivedEvent event) {
        if (event == null) {
            throw new InvalidTelemetryEventException("Rejected telemetry event: payload is null");
        }
        if (!TelemetryReceivedEvent.EVENT_TYPE.equals(event.eventType())) {
            throw reject(event, "unexpected eventType '" + event.eventType() + "'");
        }
        if (event.schemaVersion() != TelemetryReceivedEvent.CURRENT_SCHEMA_VERSION) {
            throw reject(event, "unsupported schemaVersion " + event.schemaVersion());
        }
        if (event.eventId() == null) {
            throw reject(event, "eventId is missing");
        }
        if (event.occurredAt() == null) {
            throw reject(event, "occurredAt is missing");
        }
        if (event.receivedAt() == null) {
            throw reject(event, "receivedAt is missing");
        }
        validateSource(event);
        validatePayload(event);
    }

    private void validateSource(TelemetryReceivedEvent event) {
        if (event.source() == null) {
            throw reject(event, "source is missing");
        }
        if (isBlank(event.source().sourceMessageId())) {
            throw reject(event, "sourceMessageId is missing");
        }
        if (isBlank(event.source().sensorId())) {
            throw reject(event, "sensorId is missing");
        }
        if (event.source().machineId() == null) {
            throw reject(event, "machineId is missing");
        }
    }

    private void validatePayload(TelemetryReceivedEvent event) {
        if (event.payload() == null) {
            throw reject(event, "payload is missing");
        }
        if (event.payload().temperature() == null) {
            throw reject(event, "temperature is missing");
        }
        if (event.payload().vibration() == null) {
            throw reject(event, "vibration is missing");
        }
    }

    private InvalidTelemetryEventException reject(TelemetryReceivedEvent event, String reason) {
        String sourceMessageId = event.source() == null ? null : event.source().sourceMessageId();
        return new InvalidTelemetryEventException(
                "Rejected telemetry event [eventId=%s, sourceMessageId=%s]: %s"
                        .formatted(event.eventId(), sourceMessageId, reason));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
