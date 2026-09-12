# Development Plan

## Delivery approach

Build a small, working vertical slice first and deepen reliability only after the core flow is observable and testable. Keep all work within a modular monolith unless a documented reason demonstrates that extraction is worthwhile.

## Phase 1 — Working Event-Driven Core

**Objective:** implement the end-to-end telemetry path only; alerts and maintenance/work-order workflows are explicitly excluded:

```text
Sensor Simulator -> MQTT Broker -> Spring Boot MQTT Ingestion -> Kafka Producer -> Kafka Telemetry Topic -> Kafka Consumer -> PostgreSQL -> REST API
```

**Scope:**

- Define the machine and sensor domain model.
- Implement a small sensor simulator, expected to use Python, that publishes normal telemetry for multiple configurable machines and sensors to MQTT.
- Add PostgreSQL persistence and schema migrations.
- Expose Spring Boot REST endpoints for machines, sensors, telemetry history, and latest state.
- Define and validate the documented MQTT topic/payload contract and conceptual internal Kafka event envelope, including `sourceMessageId`, `eventId`, `occurredAt`, `receivedAt`, and platform-resolved machine identity.
- Ingest MQTT telemetry and publish accepted events to Kafka.
- Consume Kafka telemetry using the documented boundary: validate -> begin database transaction -> persist immutable telemetry -> update latest-state projection -> commit -> acknowledge Kafka.
- Maintain and query the machine latest-state projection as part of the same database transaction as telemetry persistence.
- Add focused JUnit 5 unit tests with each implemented behavior.
- Add the narrow Testcontainers-backed integration baseline: PostgreSQL migrations/persistence plus one MQTT -> Kafka -> PostgreSQL telemetry-path test.

**Exit criteria:** a locally reproducible vertical slice proves that normal simulator telemetry reaches PostgreSQL through MQTT and Kafka and is returned by the REST API as history and latest state. Unit tests and the narrow critical-boundary integration baseline pass. The result documents at-least-once processing only; it makes no end-to-end exactly-once claim.

## Phase 2 — Distributed Systems Depth

**Objective:** turn explicit delivery and failure risks into demonstrated engineering work.

For every item, create a concise record containing: **problem -> solution -> test/experiment -> result**. Do not add mechanisms solely for a checklist.

**Scope:**

- Idempotency for duplicate telemetry delivery.
- Kafka consumer groups and partition-key strategy.
- Retry policy and Dead Letter Queue behavior.
- Controlled replay of failed or historical events.
- Clear transaction boundaries and delivery/commit behavior.
- Concurrency behavior, including optimistic locking where a real conflict exists.
- Redis latest-state cache only when it improves a measured or clear query need.
- Redis distributed locking only for a concrete multi-process coordination problem.
- Backpressure behavior and bounded-resource handling.
- Failure scenarios: broker/database outages, malformed events, duplicates, out-of-order messages, consumer restart, and poison messages.
- Introduce alerts and maintenance/work orders as business scenarios that exercise delivery, consistency, concurrency, and recovery behavior; do not expand them beyond what those experiments require.
- Extend the sensor simulator with abnormal values, duplicates, burst traffic, and configurable higher message rates for failure and backpressure experiments.

**Exit criteria:** each adopted distributed-systems capability has a reproducible test or experiment and a documented result, including remaining limitations.

## Phase 3 — Production Readiness

**Objective:** make the modular monolith practical to run, validate, and observe.

**Scope:**

- Docker Compose for local application dependencies.
- Expand Testcontainers-backed integration coverage and run the agreed suite in CI.
- GitHub Actions CI for build, tests, and appropriate quality checks.
- Application health checks.
- Prometheus metrics endpoint and basic application metrics.
- Grafana dashboards for essential flow and operational signals.

**Exit criteria:** a new developer can bring up local dependencies, run automated tests, and inspect basic health and telemetry-pipeline metrics; CI verifies the agreed checks on changes.

## Phase 4 — Optional, time permitting

Only select an item when it supports a clear learning or operational objective and does not jeopardize completion of Phases 1–3.

- Kubernetes.
- OpenTelemetry and distributed tracing.
- Toxiproxy.
- Load testing.
- Chaos/failure testing.
- Basic anomaly detection.

## Suggested sequencing and checkpoints

1. Before coding: agree event contract, module boundaries, API conventions, and migration strategy.
2. Build Phase 1 in narrow vertical slices; verify each boundary before adding the next.
3. At Phase 1 completion, document observed delivery semantics and choose the highest-value Phase 2 experiments.
4. Add only the Phase 2 mechanisms justified by those experiments.
5. Package, automate, and observe the resulting system in Phase 3.
6. Reassess remaining time before accepting any Phase 4 work.
