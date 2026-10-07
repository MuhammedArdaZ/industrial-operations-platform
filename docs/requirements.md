# Initial Requirements

## Product scope

Industrial Operations Platform is an event-driven backend for monitoring industrial equipment and supporting maintenance operations. It will ingest sensor telemetry, preserve a telemetry history, expose the latest known machine state, and provide a foundation for alerts and maintenance/work orders.

The initial release is a single modular Spring Boot application. It is intended as a demonstrable, maintainable engineering project that can be completed in two to three months.

### In scope

- Machine and sensor management.
- A sensor simulator that publishes development/test telemetry to MQTT; the initial implementation is expected to use Python.
- Ingesting telemetry from sensors through MQTT.
- Publishing accepted telemetry into Kafka and consuming it for durable persistence.
- Querying telemetry history and the latest known state of a machine through a REST API.
- Planned alert and maintenance/work-order modules, introduced after Phase 1 as business scenarios for distributed-systems work.
- Local reproducibility, automated tests, CI, health checks, and basic metrics in the planned phases.

### Explicitly out of scope for the initial release

- Microservice decomposition.
- Kubernetes.
- ML/AI features or anomaly detection without a clear engineering need.
- A production web frontend, mobile client, or device firmware.
- Advanced distributed-system mechanisms before their Phase 2 validation work.
- Alert rules, alert lifecycle management, and maintenance/work-order workflows in Phase 1.

## Functional requirements

### Phase 1

1. The system shall create and retrieve machines.
2. The system shall register sensors for a machine and retrieve that machine's sensors.
3. A sensor simulator shall publish normal telemetry for multiple configurable machines and sensors to the agreed MQTT topic; its initial implementation is expected to use Python.
4. The system shall accept telemetry on the documented MQTT topic and payload contract, validate the supplied sensor identity against platform-owned configuration, and resolve its authoritative machine identity from that configuration.
5. The system shall validate telemetry at ingestion, reject or record invalid messages safely, and publish valid telemetry in the documented internal event envelope to Kafka.
6. The Kafka consumer shall validate the internal event, then in one database transaction persist immutable telemetry and update the machine latest-state projection; it shall acknowledge the Kafka message only after that transaction commits.
7. The REST API shall return machine details, its sensors, telemetry history, and the latest known machine state.
8. The system shall retain sufficient event metadata to trace a persisted telemetry record back to its source message.
9. Phase 1 shall not implement alerts or maintenance/work-order workflows.

### Delivered Capabilities (Phases 1–3)

1. **Machine & Sensor Registry:** REST API endpoints to register and retrieve machines and their associated sensors.
2. **Telemetry Ingestion & Resolution:** Ingests MQTT telemetry, resolves authoritative machine identity from platform-owned sensor configuration, and creates an immutable Kafka event envelope.
3. **Decoupled Asynchronous Processing:** Kafka telemetry topic with partition key by `machineId` guarantees in-order message delivery per machine.
4. **Idempotency & Deduplication:** Pre-insert check and composite natural key constraint `(sensor_id, source_message_id)` on `telemetry` table prevents duplicate insertions from network/broker re-deliveries (ADR-005).
5. **Optimistic Locking & State Projection:** Machine latest-state projection updated atomically with telemetry persistence; JPA `@Version` concurrency control prevents lost updates under race conditions (ADR-008).
6. **Dead Letter Topic & Error Handling:** `telemetry-events-dlt` quarantines malformed poison pill messages via `FixedBackOff(1000L, 2L)` without stalling partition consumption (ADR-006).
7. **Controlled Replay:** Operator-initiated batch replay service (`TelemetryDltReplayService`) safely re-injects quarantined messages while preventing infinite replay loops (ADR-007).
8. **Chaos Sensor Simulator:** Python simulator supporting realistic streams and injection of duplicate deliveries, corrupted payloads, abnormal spikes, and bursts.
9. **Observability & Dashboards:** Prometheus endpoint (`/actuator/prometheus`), custom domain counters and percentile timers (`TelemetryMetrics`), Prometheus container (:9090), and pre-configured Grafana telemetry dashboard (:3000) (ADR-009).
10. **Containerization & CI:** Docker Compose orchestrating all 6 platform components; automated GitHub Actions CI verifying the complete test suite on every commit.

### Future functional capabilities (Phase 4 / Extensions)

- Business alert rules derived from operational telemetry conditions (e.g. temperature > 90°C).
- Maintenance work orders associated with machines and trigger alerts.
- Live Factory Floor Web UI (WebSocket / Server-Sent Events).
- OpenAPI / Swagger interactive documentation (`springdoc-openapi`).

## Non-functional requirements

- **Maintainability:** Clear modular monolith boundaries (`machine`, `sensor`, `ingestion`, `telemetry`, `state`, `common`), minimal dependencies, and Flyway versioned migrations (`V1`, `V2`, `V3`).
- **Correctness:** Strict boundary validation (`TelemetryEventValidator`, `MqttTelemetryPayload`); UTC/offset-aware event times; tested persistence and state-projection ordering.
- **Reliability:** At-least-once transport combined with idempotent persistence (Flyway V2) and optimistic locking (Flyway V3, ADR-008) guarantees consistency. Poison pills are safely quarantined to DLT (ADR-006).
- **Observability:** Structured logs; Actuator `/actuator/health` and `/actuator/prometheus`; Micrometer domain metrics (ingestion count, duplicate count by reason, latency percentiles); Grafana visualization (:3000) (ADR-009).
- **Testability:** Comprehensive automated test suite (JUnit 5 unit tests and Testcontainers-backed integration tests running against ephemeral PostgreSQL and Kafka containers), executed automatically on GitHub Actions CI.
- **Security:** Environment-driven configuration; no hardcoded secrets; validated input boundaries.
- **Operational usability:** Single-command local environment via `docker compose up -d` bringing up all services and observability tools out-of-the-box.
- **Performance:** Non-blocking in-memory metric recording; Kafka partitioning by machine; optimistic concurrency control avoiding pessimistic row locks.

## Assumptions to validate during implementation

- Each sensor has a stable identifier and belongs to one machine.
- A source telemetry message has a stable `sourceMessageId`, source-observation time (`occurredAt`), and sensor identity. Ingestion records platform-receipt time (`receivedAt`), resolves the authoritative machine from the sensor configuration, and creates a separate internal `eventId`.
- Latest state is derived from the most recent valid persisted telemetry according to an explicitly defined ordering rule.
- MQTT topic patterns, REST resource shapes, alert rules, retention period, and user access control will be specified before their respective implementations.
