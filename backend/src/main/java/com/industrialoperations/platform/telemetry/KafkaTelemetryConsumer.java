package com.industrialoperations.platform.telemetry;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.industrialoperations.platform.common.Measurements;

@Component
public class KafkaTelemetryConsumer {

    private final TelemetryEventValidator telemetryEventValidator;
    private final TelemetryService telemetryService;

    public KafkaTelemetryConsumer(TelemetryEventValidator telemetryEventValidator,
            TelemetryService telemetryService) {
        this.telemetryEventValidator = telemetryEventValidator;
        this.telemetryService = telemetryService;
    }

    @KafkaListener(topics = "${platform.kafka.telemetry-topic}")
    public void consume(TelemetryReceivedEvent event) {
        telemetryEventValidator.validate(event);
        telemetryService.recordTelemetry(toCommand(event));
    }

    private RecordTelemetryCommand toCommand(TelemetryReceivedEvent event) {
        return new RecordTelemetryCommand(
                event.eventId(),
                event.source().sourceMessageId(),
                event.source().sensorId(),
                event.source().machineId(),
                event.occurredAt(),
                event.receivedAt(),
                new Measurements(event.payload().temperature(), event.payload().vibration()));
    }
}
