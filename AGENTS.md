# Industrial Operations Platform — Agent Guide

## Project purpose

Build a realistically finishable, event-driven backend for industrial equipment monitoring and maintenance. The project is deliberately focused on backend, distributed-systems, and enterprise engineering practice rather than feature breadth.

## Engineering principles

- Start as a modular monolith: one deployable Spring Boot application with clearly separated modules and explicit module boundaries.
- Do not introduce microservices, Kubernetes, ML/AI, or other infrastructure without a concrete, documented problem that requires it.
- Prefer the simplest maintainable design that meets the current phase's requirements.
- Keep dependencies purposeful and minimal. Do not add a dependency merely to demonstrate a technology.
- Treat tests as implementation work. New behavior should include appropriate unit and integration coverage.
- Document every major architectural decision, including the context, decision, consequences, and alternatives when useful.
- Design for failure: make data ownership, delivery semantics, retries, and transaction boundaries explicit.
- Preserve backward-compatible API and event changes unless a documented migration is included.

## Target stack

- Java 25 and Spring Boot 3.5
- PostgreSQL 16+ for durable application data and Flyway versioned migrations
- Eclipse Mosquitto for MQTT device telemetry ingress
- Apache Kafka 3.7+ (KRaft) for internal event transport
- Docker Compose for local infrastructure orchestration
- JUnit 5 and Testcontainers for automated testing
- GitHub Actions for CI
- Prometheus and Grafana for Phase 3 observability
- *(Redis was evaluated in Phase 2 and deliberately deferred in favor of SQL-level optimistic locking)*

## Current Status & Architectural Evolution

Phases 1, 2, and 3 are **completed**:
- **DLT & Controlled Replay:** Implemented and verified via `telemetry-events-dlt`, `FixedBackOff`, `DeadLetterPublishingRecoverer`, and `TelemetryDltReplayService` (ADR-006, ADR-007).
- **Idempotency & Concurrency:** Delivered via Flyway V2 composite constraints `(sensor_id, source_message_id)` and Flyway V3 JPA `@Version` optimistic locking (ADR-005, ADR-008).
- **Redis Deferred (Design Decision):** Redis caching and distributed locking were evaluated during Phase 2. PostgreSQL optimistic locking (`@Version`) cleanly eliminated lost updates on the projection table without the operational overhead, cache invalidation races, and split-brain risks of introducing a Redis cluster.
- **Alerts & Maintenance Workflows:** Retained as candidate Phase 4 business scenarios. The distributed systems resilience experiments were validated directly on the core telemetry pipeline.
- **Observability Stack:** Fully delivered via Spring Boot Actuator, custom Micrometer metrics (`telemetry_ingested_total`, `telemetry_duplicates_total`, `telemetry_processing_duration_seconds`), Prometheus pull scraping (:9090), and pre-provisioned Grafana dashboards (:3000) (ADR-009).
- **Verification:** Automated JUnit 5 unit and Testcontainers-backed integration test suite executing on every commit in GitHub Actions CI.

## Architecture rules

- The platform is a modular monolith, not a collection of microservices.
- Keep domain modules cohesive: machines, sensors, telemetry, machine state, alerts, and maintenance/work orders. Alerts and maintenance/work orders are candidate Phase 4 expansions.
- Ingestion telemetry follows: sensor simulator -> MQTT broker -> Spring Boot MQTT ingestion -> Kafka producer -> Kafka telemetry topic -> Kafka consumer -> PostgreSQL -> REST API.
- Kafka is the asynchronous boundary between ingestion and persistence; do not bypass it for normal telemetry processing.
- Treat telemetry events as immutable facts. Maintain latest machine state separately as a query-optimized projection.
- Make source-message identity, internal-event identity, source/platform timestamps, schema/version expectations, and error handling explicit before implementing producers or consumers.
- Validate telemetry sensor identity against platform-owned sensor configuration. Resolve the machine from that relationship; never copy an untrusted producer-supplied machine ID into an internal event.
- The consumer boundary is: validate message -> check composite natural key deduplication -> begin database transaction -> persist immutable telemetry -> update latest-state projection -> commit database transaction -> acknowledge Kafka message. Telemetry persistence and state projection are one database consistency boundary.
- At-least-once delivery is paired with application and database-level idempotency (Flyway V2) and optimistic concurrency control (Flyway V3, ADR-008). Poison pills are quarantined to `telemetry-events-dlt` (ADR-006).

## Development phases

1. **Working Event-Driven Core [COMPLETED]**: a sensor simulator and machine/sensor management, PostgreSQL persistence, REST API, MQTT ingestion, Kafka producer/consumer, telemetry persistence, and latest-state querying.
2. **Distributed Systems Depth [COMPLETED]**: demonstrable work on idempotency (`(sensor_id, source_message_id)`, Flyway V2, ADR-005), partition ordering key (`machineId`, ADR-004), poison pill quarantine & DLT (`telemetry-events-dlt`, `FixedBackOff`, ADR-006), controlled replay (`TelemetryDltReplayService`, ADR-007), and optimistic concurrency locking (`@Version`, Flyway V3, ADR-008). Failure experiments recorded in `docs/failure-scenarios.md`. *(Redis caching/locks and alert/maintenance business modules were evaluated and deferred to Phase 4 in favor of SQL-level optimistic locking).*
3. **Production Readiness [COMPLETED]**: Docker Compose (all 6 services), expanded Testcontainers automated test suite, GitHub Actions CI, Actuator health checks, Prometheus metrics endpoint (`TelemetryMetrics`), and Grafana dashboard (ADR-009).
4. **Optional (Future Extensions)**: Business alert rules & maintenance/work orders, Live Factory Floor Web UI (WebSocket / SSE), OpenAPI/Swagger interactive UI (`springdoc-openapi`), Kubernetes, OpenTelemetry/tracing, Toxiproxy, and anomaly detection.

## Coding expectations

- Use clear package/module ownership and dependency direction; controllers and infrastructure adapters must not contain domain rules.
- Validate external REST and MQTT inputs at the boundary. Return actionable API errors without exposing internals.
- Use Flyway versioned SQL migrations for PostgreSQL schema evolution; never depend on ad-hoc production schema changes.
- Use UTC/offset-aware timestamps and state time semantics explicitly.
- Avoid shared mutable state. Apply transaction boundaries deliberately and document consistency trade-offs.
- Add structured, correlation-friendly logs for ingestion and event processing without logging sensitive data.
- Write focused JUnit 5 unit tests with implementation. Establish a narrow Testcontainers-backed Phase 1 integration baseline for the PostgreSQL, Kafka, and MQTT telemetry path; expand its coverage and run it in CI in Phase 3.
- Keep configuration environment-driven, with safe local defaults and no committed secrets.
- Update `docs/` and synchronize `README.md` whenever a major decision, flow, requirement, or phase scope changes.

## Learning-oriented collaboration

This project is built as a learning exercise. Do not autonomously implement substantial application code unless the user explicitly asks for the completed code.

For each implementation step:

1. Explain the goal, architectural context, affected module boundaries, and relevant trade-offs before proposing code.
2. Propose one small, learnable task at a time and let the user implement it when they choose to do so.
3. Design class skeletons and critical code paths collaboratively when useful, explaining why each responsibility belongs where it does.
4. Review the user's implementation, identify strengths and issues, and provide concrete feedback before moving on.
5. Write or complete code only when the user explicitly requests that help.

This collaboration rule does not prevent read-only inspection, design discussion, test review, or giving small illustrative snippets.

## Workflow for future agents

1. Read `docs/requirements.md`, `docs/architecture.md`, `docs/development-plan.md`, and relevant `docs/adr/` records before changing behavior.
2. Confirm which phase the work belongs to and avoid pulling later-phase scope forward.
3. Follow the learning-oriented collaboration rules: explain first, propose a small task, and wait for explicit authorization before writing substantial code.
4. State the problem, proposed design, affected boundaries, and tests before non-trivial changes.
5. Make the smallest coherent change, then run the relevant verification.
6. Record material architecture decisions in the documentation (or an ADR when introduced).
7. Keep `README.md` in sync with completed roadmap milestones, ADR references, migrations, and test additions after each milestone.
8. Do not install dependencies, alter infrastructure, or make external changes unless the task authorizes it.
