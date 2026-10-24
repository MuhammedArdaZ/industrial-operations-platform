# Development Plan

## Delivery approach

Build a small, working vertical slice first and deepen reliability only after the core flow is observable and testable. Keep all work within a modular monolith unless a documented reason demonstrates that extraction is worthwhile.

## Phase 1 — Working Event-Driven Core [COMPLETED]

**Objective:** implement the end-to-end telemetry path only; alerts and maintenance/work-order workflows are explicitly excluded:

```text
Sensor Simulator -> MQTT Broker -> Spring Boot MQTT Ingestion -> Kafka Producer -> Kafka Telemetry Topic -> Kafka Consumer -> PostgreSQL -> REST API
```

**Scope & Delivery Summary:**

- [x] Defined machine and sensor domain model.
- [x] Implemented Python sensor simulator publishing telemetry for configurable machines and sensors to MQTT (`simulator/sensor_simulator.py`).
- [x] Added PostgreSQL persistence with Flyway `V1__create_phase_1_baseline.sql`.
- [x] Exposed Spring Boot REST endpoints for machines, sensors, telemetry history, and latest state (`/api/v1/...`).
- [x] Validated MQTT topic/payload contract and Kafka internal event envelope (ADR-001, ADR-002, ADR-004).
- [x] Ingested MQTT telemetry and published validated events to Kafka.
- [x] Consumed Kafka telemetry within a single transactional boundary: validate -> persist immutable telemetry -> update latest-state projection -> commit DB -> acknowledge Kafka (ADR-003).
- [x] Added focused JUnit 5 unit and Testcontainers integration tests verifying the baseline.

**Exit criteria:** **MET.** Locally reproducible vertical slice operating at-least-once across MQTT, Kafka, and PostgreSQL.

## Phase 2 — Distributed Systems Depth [COMPLETED]

**Objective:** turn explicit delivery and failure risks into demonstrated engineering work. Recorded in detail in `docs/failure-scenarios.md` and ADR-004 through ADR-008.

**Scope & Delivery Summary:**

- [x] **Idempotency & deduplication:** Enforced natural composite key `(sensor_id, source_message_id)` and unique `event_id` in Flyway V2 (ADR-005).
- [x] **Kafka partition-key ordering:** Formalized FIFO ordering per machine using authoritative `machineId` partition key, preventing cross-metric race conditions (ADR-004).
- [x] **Dead Letter Topic (DLT):** Configured `telemetry-events-dlt` with `FixedBackOff` to quarantine poison pills without blocking partition consumption (ADR-006).
- [x] **Controlled replay:** Built `TelemetryDltReplayService` with ping-pong loop prevention (ADR-007).
- [x] **Concurrency & optimistic locking:** Enforced JPA `@Version` on `MachineLatestState` via Flyway V3, preventing lost updates (ADR-008). Redis locking evaluated and deferred as unnecessary operational overhead in favor of database-level optimistic locking.
- [x] **Failure matrix experiments:** Consumer downtime buffering, out-of-order logical clock checks, poison pill quarantine, and concurrent race collisions tested and documented.
- [x] **Simulator chaos controls:** Extended simulator with `--chaos-duplicates`, `--chaos-malformed`, `--chaos-abnormal`, and `--burst-size`.

**Exit criteria:** **MET.** Core distributed failure modes (poison pills, out-of-order events, consumer downtime, concurrent projection updates) are verified by integration tests and recorded in `docs/failure-scenarios.md`, with known DR/broker outage boundaries explicitly documented.

## Phase 3 — Production Readiness [COMPLETED]

**Objective:** make the modular monolith practical to run, validate, and observe.

**Scope & Delivery Summary:**

- [x] **Docker Compose orchestration:** Complete stack running PostgreSQL, Mosquitto, Kafka KRaft, Spring Boot backend, Prometheus, and Grafana (`docker-compose.yml`).
- [x] **Expanded Testcontainers suite:** Full automated test suite (unit and integration tests) passing (`./mvnw clean verify`).
- [x] **GitHub Actions CI:** Automated pipeline building the JAR and running all Testcontainers tests on pull requests (`.github/workflows/ci.yml`).
- [x] **Application health checks:** Spring Boot Actuator `/actuator/health` reporting live subsystem status.
- [x] **Prometheus metrics:** Domain counters (`telemetry_ingested_total`, `telemetry_duplicates_total` with `reason` tag) and percentile timers (`telemetry_processing_time_seconds`) at `/actuator/prometheus` (ADR-009).
- [x] **Grafana dashboards:** Pre-provisioned dashboards on port `3000` monitoring live ingestion, duplicates, latencies, and JVM memory.

**Exit criteria:** **MET.** `docker compose up -d` boots the entire stack; `./mvnw clean verify` validates the complete test suite; Prometheus and Grafana provide instant visual observability.

## Phase 4 — Alerting Engine & Hysteresis [COMPLETED]

**Objective:** implement real-time operational threshold anomaly detection, anti-spam row deduplication, noise suppression with hysteresis auto-clearing, and operator incident acknowledgement workflows.

**Scope & Delivery Summary:**

- [x] **Domain modeling:** Defined `Alert`, `AlertStatus` (`ACTIVE`, `ACKNOWLEDGED`, `RESOLVED`), `AlertSeverity`, and `AlertRuleType` (`HIGH_TEMPERATURE`, `HIGH_VIBRATION`).
- [x] **Database schema evolution:** Flyway migrations `V4__create_alerts_table.sql` (alerts table with composite indexes) and `V5__alerts_partial_unique_index.sql` (partial unique index on open alerts for concurrency safety; ADR-010).
- [x] **Anti-spam row deduplication:** Stateful in-place updates of `current_value` on existing open alerts (`ACTIVE` + `ACKNOWLEDGED`) to prevent notification floods and duplicate alerts during prolonged threshold breaches.
- [x] **Hysteresis auto-clearing:** Asymmetric trigger and recovery thresholds (Temperature: trigger ≥ 90.0°C, clear < 85.0°C; Vibration: trigger ≥ 0.80g, clear < 0.60g) eliminating threshold flapping.
- [x] **In-pipeline transaction integration:** Direct invocation inside `TelemetryService.recordTelemetry(...)`, guarded by latest-state projection check to suppress stale out-of-order evaluations.
- [x] **REST API surface:** Endpoints `/api/v1/alerts`, `/api/v1/alerts/machines/{machineId}`, and `POST /api/v1/alerts/{alertId}/acknowledge` for facility-wide and machine-level incident management.
- [x] **Automated verification:** Testcontainers integration tests (`AlertIntegrationTest.java`) verifying the full alert lifecycle (`ACTIVE` → `ACKNOWLEDGED` → `RESOLVED`), open-alert deduplication continuity, and out-of-order telemetry suppression.
- [x] **Cloud deployment:** Multi-stage Docker build deployed to AWS EC2 (`<EC2_PUBLIC_IP>`), streaming telemetry and evaluating alerts while the instance runs (ADR-011).

**Exit criteria:** **MET.** Alerting engine operates with zero duplicate spam under prolonged spikes and auto-clears cleanly across telemetry streams.

## Phase 5 — Optional Extensions, time permitting

Only select an item when it supports a clear learning or operational objective:

- Maintenance work orders associated with machines and triggered alerts.
- Live Factory Floor Web UI (WebSocket / Server-Sent Events).
- OpenAPI / Swagger interactive documentation (`springdoc-openapi`).
- OpenTelemetry and distributed tracing.
- Toxiproxy and network partition chaos testing.
- Kubernetes deployment manifests.

## Suggested sequencing and checkpoints

1. Before coding: agree event contract, module boundaries, API conventions, and migration strategy.
2. Build Phase 1 in narrow vertical slices; verify each boundary before adding the next.
3. At Phase 1 completion, document observed delivery semantics and choose the highest-value Phase 2 experiments.
4. Add only the Phase 2 mechanisms justified by those experiments.
5. Package, automate, and observe the resulting system in Phase 3.
6. Build operational alerting and lifecycle management in Phase 4.
7. Reassess remaining time before accepting any Phase 5 work.
