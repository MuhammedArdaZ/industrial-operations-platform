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

- Java and Spring Boot
- PostgreSQL for durable application data
- MQTT for device telemetry ingress
- Apache Kafka for internal event transport
- Redis for later latest-state caching and distributed-locking experiments
- Docker Compose for local infrastructure
- JUnit 5 and Testcontainers for testing
- GitHub Actions for CI
- Prometheus and Grafana for Phase 3 observability

## Architecture rules

- Phase 1 is a modular monolith, not a collection of services.
- Keep domain modules cohesive: machines, sensors, telemetry, machine state, alerts, and maintenance/work orders. Alerts and maintenance/work orders are planned modules, but are not implemented in Phase 1.
- In Phase 1, telemetry follows: sensor simulator -> MQTT broker -> Spring Boot MQTT ingestion -> Kafka producer -> Kafka telemetry topic -> Kafka consumer -> PostgreSQL -> REST API.
- Kafka is the asynchronous boundary between ingestion and persistence; do not bypass it for normal telemetry processing.
- Treat telemetry events as immutable facts. Maintain latest machine state separately as a query-optimized projection.
- Make source-message identity, internal-event identity, source/platform timestamps, schema/version expectations, and error handling explicit before implementing producers or consumers.
- Validate telemetry sensor identity against platform-owned sensor configuration. Resolve the machine from that relationship; never copy an untrusted producer-supplied machine ID into an internal event.
- The Phase 1 consumer boundary is: validate message -> begin database transaction -> persist immutable telemetry -> update latest-state projection -> commit database transaction -> acknowledge Kafka message. Telemetry persistence and state projection are one database consistency boundary.
- This Phase 1 ordering is at-least-once processing, not end-to-end exactly-once processing. Idempotency and failure semantics are major Phase 2 work.
- Do not add Redis, DLQs, replay facilities, distributed locks, or advanced retry behavior until their Phase 2 problem statements and experiments are documented.

## Development phases

1. **Working Event-Driven Core**: a sensor simulator and machine/sensor management, PostgreSQL persistence, REST API, MQTT ingestion, Kafka producer/consumer, telemetry persistence, and latest-state querying. Alerts and maintenance/work orders are excluded.
2. **Distributed Systems Depth**: demonstrable work on idempotency, consumer groups, partitioning, retry/DLQ/replay, transactions, concurrency, Redis cache/locks, backpressure, and failures, with alerts and maintenance/work orders introduced as useful business scenarios.
3. **Production Readiness**: Docker Compose, expanded Testcontainers integration coverage, CI, health checks, Prometheus, Grafana, and basic metrics.
4. **Optional**: Kubernetes, OpenTelemetry/tracing, Toxiproxy, load/chaos testing, and basic anomaly detection only if time remains and there is a clear benefit.

## Coding expectations

- Use clear package/module ownership and dependency direction; controllers and infrastructure adapters must not contain domain rules.
- Validate external REST and MQTT inputs at the boundary. Return actionable API errors without exposing internals.
- Use Flyway versioned SQL migrations for PostgreSQL schema evolution; never depend on ad-hoc production schema changes.
- Use UTC/offset-aware timestamps and state time semantics explicitly.
- Avoid shared mutable state. Apply transaction boundaries deliberately and document consistency trade-offs.
- Add structured, correlation-friendly logs for ingestion and event processing without logging sensitive data.
- Write focused JUnit 5 unit tests with implementation. Establish a narrow Testcontainers-backed Phase 1 integration baseline for the PostgreSQL, Kafka, and MQTT telemetry path; expand its coverage and run it in CI in Phase 3.
- Keep configuration environment-driven, with safe local defaults and no committed secrets.
- Update `docs/` when a major decision, flow, requirement, or phase scope changes.

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
7. Do not install dependencies, alter infrastructure, or make external changes unless the task authorizes it.
