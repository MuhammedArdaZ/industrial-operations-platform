package com.industrialoperations.platform.telemetry;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public class TelemetryMetrics {

    private final Counter ingestedCounter;
    private final Counter duplicateSensorMessageCounter;
    private final Counter duplicateEventCounter;
    private final Timer processingTimer;

    public TelemetryMetrics(MeterRegistry meterRegistry) {
        this.ingestedCounter = Counter.builder("telemetry.ingested.total")
                .description("Total number of successfully ingested telemetry records")
                .register(meterRegistry);

        this.duplicateSensorMessageCounter = Counter.builder("telemetry.duplicates.total")
                .tag("reason", "sensor_message_id")
                .description("Duplicate telemetries detected by sensor and message id")
                .register(meterRegistry);

        this.duplicateEventCounter = Counter.builder("telemetry.duplicates.total")
                .tag("reason", "event_id")
                .description("Duplicate telemetries detected by event id")
                .register(meterRegistry);

        this.processingTimer = Timer.builder("telemetry.processing.time")
                .description("Time taken to process and persist telemetry records")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    public void incrementIngested() {
        ingestedCounter.increment();
    }

    public void incrementDuplicateSensorMessage() {
        duplicateSensorMessageCounter.increment();
    }

    public void incrementDuplicateEvent() {
        duplicateEventCounter.increment();
    }

    public <T> T recordProcessingTime(java.util.function.Supplier<T> action) {
        return processingTimer.record(action);
    }
}
