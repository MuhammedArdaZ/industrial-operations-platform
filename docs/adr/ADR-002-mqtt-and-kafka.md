# ADR-002: Use MQTT for Ingress and Kafka for Internal Telemetry Events

## Context

Industrial sensors need a lightweight telemetry-ingress protocol, while the application needs a durable asynchronous handoff between ingestion and persistence that can later support consumer groups, partitioning, replay, and failure experiments.

## Decision

Accept sensor telemetry through MQTT using the conceptual topic `industrial/v1/telemetry/{sensorId}`. The Spring Boot MQTT ingestion adapter validates the topic and payload, verifies the sensor against platform-owned configuration, resolves the authoritative machine from that relationship, and publishes a conceptual `TelemetryReceived` event envelope to a Kafka telemetry topic. A Kafka consumer processes the event for PostgreSQL persistence and latest-state projection.

## Consequences

- MQTT aligns the ingestion boundary with device-oriented publish/subscribe telemetry.
- Kafka decouples ingestion from persistence and makes later distributed-systems work explicit.
- The project operates both an MQTT broker and Kafka, increasing local infrastructure needs in later phases.
- The internal event envelope needs deliberate versioning and evolution rules.
- MQTT producers do not control the authoritative machine identity; an invalid or unknown sensor is not published to Kafka in Phase 1.

## Alternatives considered

- **REST-only telemetry ingestion:** rejected because it does not model the intended device messaging flow.
- **MQTT directly to PostgreSQL:** rejected because it bypasses the internal asynchronous event boundary and limits replay/consumer experiments.
- **Kafka directly from sensors:** rejected because MQTT is the chosen device-ingress protocol.
