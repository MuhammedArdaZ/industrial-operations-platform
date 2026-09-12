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

### Future functional capabilities

- Alerts derived from defined operational conditions, introduced in Phase 2 as business scenarios for distributed-systems experiments.
- Maintenance/work orders associated with machines and alerts, introduced in Phase 2 as business scenarios for distributed-systems experiments.
- Controlled replay and failure recovery for telemetry processing.
- Sensor-simulator scenarios for abnormal values, duplicate messages, burst traffic, and increased message rates for load testing.

## Non-functional requirements

- **Maintainability:** clear modular boundaries, conventional Spring Boot design, concise documentation, and minimal dependencies.
- **Correctness:** validation at system boundaries; explicit event contracts; UTC/offset-aware event times; tested persistence and state-projection behavior.
- **Reliability:** Phase 1 uses at-least-once processing. A database transaction makes telemetry persistence and latest-state projection consistent with each other, but does not provide end-to-end exactly-once processing. Phase 2 will establish idempotency, retry, DLQ, replay, and failure handling behavior through tests/experiments.
- **Observability:** structured logs from Phase 1; health checks and Prometheus-compatible application metrics in Phase 3.
- **Testability:** unit tests are required for domain/application behavior. Phase 1 includes a small Testcontainers-backed integration baseline that verifies the critical PostgreSQL, Kafka, and MQTT telemetry path; Phase 3 expands this coverage and runs it in CI.
- **Security:** configuration is environment-driven; no secrets are committed; external input is validated; authentication/authorization scope will be explicitly defined before exposure beyond local development.
- **Operational usability:** local environment will be reproducible with Docker Compose in Phase 3 and CI will run relevant automated checks.
- **Performance:** no premature performance target is assumed. Baselines and bottlenecks will be measured before optimization; backpressure is a Phase 2 concern.

## Assumptions to validate during implementation

- Each sensor has a stable identifier and belongs to one machine.
- A source telemetry message has a stable `sourceMessageId`, source-observation time (`occurredAt`), and sensor identity. Ingestion records platform-receipt time (`receivedAt`), resolves the authoritative machine from the sensor configuration, and creates a separate internal `eventId`.
- Latest state is derived from the most recent valid persisted telemetry according to an explicitly defined ordering rule.
- MQTT topic patterns, REST resource shapes, alert rules, retention period, and user access control will be specified before their respective implementations.
