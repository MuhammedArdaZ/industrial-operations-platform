package com.industrialoperations.platform.telemetry;

/**
 * Publishes internal telemetry events. Telemetry owns this contract so that the ingestion
 * boundary can hand off an observation without depending on the transport behind it.
 */
public interface TelemetryEventPublisher {

    void publish(TelemetryReceivedEvent telemetryReceivedEvent);
}
