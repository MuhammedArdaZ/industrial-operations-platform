# Platform Architecture & System Design

## Architectural style

The system starts as a modular monolith: one Spring Boot deployment and one codebase, organized into cohesive domain/application modules. This keeps development, debugging, testing, and deployment straightforward while preserving boundaries that may justify extraction later.

No microservices are planned. A service is extracted only when concrete evidence—such as independently scaling a workload, an ownership boundary, reliability isolation, or deployment cadence—outweighs the added operational complexity. Alerts and maintenance/work orders remain planned domain modules for future business workflow expansion.

## Primary components

| Component | Responsibility | Role in Platform |
| --- | --- | --- |
| Sensor simulator | Development/test telemetry source | Python program publishing normal and chaos telemetry (duplicates, abnormal spikes, bursts) to MQTT |
| REST API | Management and query interface | Machines, sensors, telemetry history, latest state, and operational alerts |
| MQTT ingestion adapter | External device boundary | Subscribes to telemetry, validates contract, resolves sensor-to-machine mapping, forwards accepted events |
| Kafka producer | Event handoff | Publishes validated telemetry envelopes partitioned by `machineId` to `telemetry-events` |
| Kafka consumer | Asynchronous processing | Consumes telemetry, evaluates deduplication, persists history, updates latest-state projection, and evaluates threshold alerts |
| Alert Engine | Anomaly detection & lifecycle | Detects threshold breaches, deduplicates active alerts (anti-spam), auto-clears via hysteresis, and manages alert lifecycle (ADR-010) |
| Dead Letter Topic & Replay | Failure quarantine & recovery | Quarantines poison pills in `telemetry-events-dlt` and supports controlled operator replay via `TelemetryDltReplayService` (ADR-006, ADR-007) |
| Application/domain modules | Business rules and use cases | Machine, sensor, telemetry, latest state, alerts, and metrics instrumentation |
| PostgreSQL | Durable system of record | Machine/sensor data, immutable deduplicated telemetry history, optimistic-locked state projection, and stateful alerts |
| Prometheus & Grafana | Real-time observability | Scrapes `/actuator/prometheus` (:9090) and displays live throughput, duplicates, percentiles, and JVM metrics (:3000) (ADR-009) |

## Telemetry Event Flow & Infrastructure

```text
┌───────────────────┐       MQTT        ┌───────────────────────────────────────────────┐
│  Sensor Simulator │ ────────────────> │          Spring Boot Backend (:8080)          │
└───────────────────┘                   │                                               │
                                        │  ┌────────────────────────┐                   │
                                        │  │ MQTT Ingestion Adapter │                   │
                                        │  └───────────┬────────────┘                   │
                                        └──────────────┼────────────────────────────────┘
                                                       │ Kafka Event Envelope
                                                       ▼
┌───────────────────┐      Consumer     ┌─────────────────────────────┐
│    PostgreSQL     │ <──────────────── │   Kafka Telemetry Topic     │
│ (System of Record)│                   └──────────────┬──────────────┘
└─────────┬─────────┘                                  │ Poison Pill / Fatal Error
          │                                            ▼
          │ Read State & Alerts                 ┌─────────────────────────────┐
          ▼                                     │  Dead Letter Topic (-dlt)   │
┌───────────────────┐                           └──────────────┬──────────────┘
│ REST API (/api/v1)│                                          │ Controlled Replay
└───────────────────┘                                          ▼
                                                (Re-injected into Pipeline)
┌─────────────────────────────────┐
│ Spring Boot Actuator (:8080)    │
│      /actuator/prometheus       │
└────────────────┬────────────────┘
                 │ Scrape (every 5s)
                 ▼
┌───────────────────┐     Query API     ┌─────────────────────────────┐
│ Prometheus (:9090)│ ────────────────> │  Grafana Dashboard (:3000)  │
└───────────────────┘                   └─────────────────────────────┘
```

The sensor simulator is outside the Spring Boot application. It will eventually generate realistic telemetry for multiple machines and sensors and publish it to MQTT. Its scenarios will grow from normal telemetry and configurable message frequency to abnormal values, duplicate messages, burst traffic, and increased rates for load testing.

The application owns the MQTT and Kafka adapters as modules within the same deployable. Kafka remains the asynchronous handoff even though the producer and consumer begin in the same application; this makes delivery, partitioning, replay, and failure behavior explicit without introducing premature services.

## Initial internal telemetry event envelope

The Kafka telemetry topic carries an initial conceptual event contract. It is a design reference, not a final implementation or Java class definition.

```json
{
  "eventId": "...",
  "eventType": "TelemetryReceived",
  "schemaVersion": 1,
  "occurredAt": "...",
  "receivedAt": "...",
  "source": {
    "sourceMessageId": "...",
    "sensorId": "...",
    "machineId": "..."
  },
  "payload": {
    "temperature": 85.4,
    "vibration": 2.1
  }
}
```

- **Source message identity (`sourceMessageId`)** is generated by the sensor/simulator for one telemetry observation and is preserved when that same source message is redelivered. Scoped by `sensorId`, it is the primary deduplication key since Phase 2 (ADR-005).
- **Internal event identity (`eventId`)** identifies the `TelemetryReceived` event created by the platform. It supports tracing between ingestion and Kafka, and is distinct from `sourceMessageId`: a redelivered MQTT message produces a new `eventId`, which is why deduplication relies on `(sensorId, sourceMessageId)` and treats `eventId` only as a secondary guard against Kafka-level redelivery.
- **Event type (`eventType`)** identifies the semantic meaning of a message and makes future topic consumers safer to evolve.
- **Schema version (`schemaVersion`)** provides an explicit starting point for compatible schema evolution.
- **Source observation time (`occurredAt`)** is when the measurement occurred at the source and supports ordering and state-projection rules.
- **Platform receipt time (`receivedAt`)** is when the platform received the MQTT message, enabling ingestion-lag diagnostics independently of source time.
- **Sensor identity** identifies the producing device and is validated against platform-owned sensor configuration.
- **Machine identity** is the authoritative machine resolved by ingestion/application logic from the validated sensor relationship; it is not an arbitrary value copied from an MQTT producer.
- **Telemetry payload** contains the measured values while keeping transport metadata separate.

This contract is implemented by `TelemetryReceivedEvent` (`schemaVersion` 1) and serialized as JSON without Java type headers (ADR-004). It is the basis for the idempotency, DLT, and replay work in ADR-005 to ADR-007.

## Phase 1 MQTT contract

The MQTT contract is intentionally small and separate from the internal Kafka event envelope.

- **Proposed topic:** `industrial/v1/telemetry/{sensorId}`.
- **Payload:** JSON containing `sourceMessageId`, `sensorId`, `occurredAt`, and `measurements`. `measurements` initially carries `temperature` and `vibration` numeric values.

```json
{
  "sourceMessageId": "...",
  "sensorId": "...",
  "occurredAt": "...",
  "measurements": {
    "temperature": 85.4,
    "vibration": 2.1
  }
}
```

`sourceMessageId` is generated by the sensor/simulator once for an observation and must be retained when that observation is redelivered. `sensorId` in the topic and payload must agree. The producer does not supply `machineId` in the MQTT payload: the ingestion boundary looks up the registered sensor and resolves its machine using the platform-owned Sensor -> Machine relationship.

The ingestion adapter validates topic format, payload shape, required fields, timestamp format, measurement types, topic/payload sensor agreement, and that the sensor exists and is associated with a machine. It records `receivedAt` using the platform clock, generates `eventId`, and publishes only a validated, resolved internal event to Kafka.

The MQTT acknowledgement follows the Kafka acknowledgement (ADR-012):

```text
MQTT message (QoS 1, persistent session)
  -> validate and resolve sensor -> machine
       invalid: log and discard (PUBACK sent; redelivery cannot fix it)
  -> KafkaTemplate.send(...).get(KAFKA_PUBLISH_TIMEOUT)   (send blocks at most KAFKA_MAX_BLOCK_MS)
       failure or timeout: TelemetryPublishException, no PUBACK, connection drops, broker redelivers
  -> messageArrived returns -> Paho sends PUBACK
```

Downstream, poison pills go straight to the Dead Letter Topic (`telemetry-events-dlt`), and other failures get `FixedBackOff(1000L, 2L)` retries before being dead-lettered, with controlled operator replay via `TelemetryDltReplayService` (ADR-006, ADR-007). Database connection failures are the exception: the consumer retries them with an exponential back-off (capped at 30 s) until PostgreSQL returns and never dead-letters them, so a database outage pauses consumption instead of quarantining valid telemetry.

## Data and consistency model

- **Machine** is the managed industrial asset.
- **Sensor** belongs to a machine and identifies the origin of telemetry. A sensor is a multi-measurement unit: one observation carries the full measurement set the unit reports, so `sensors.type` describes the kind of unit rather than a single measured quantity. When a unit that also reports pressure is introduced, it reports pressure alongside the existing measurements under a new `schemaVersion`.
- **Telemetry** is an immutable observation associated with a sensor and authoritative machine. It retains `sourceMessageId`, `eventId`, `occurredAt`, and `receivedAt` for traceability and idempotency deduplication (ADR-005).
- **Machine latest state** is a separate, query-oriented projection derived from valid persisted telemetry. It is not the source of historical truth. Its ordering rule is strict: an observation replaces the stored state only when its `occurredAt` is strictly after the stored `occurredAt`. An older observation is ignored, and an observation with an identical `occurredAt` leaves the existing state in place, so the first arrival wins a tie. Concurrency race conditions are guarded by JPA `@Version` optimistic locking (ADR-008).
- **Alert** is a domain module (`com.industrialoperations.platform.alert`) providing operational anomaly detection, anti-spam deduplication, and hysteresis auto-clearing (ADR-010).
- **Maintenance/work order** is a reserved domain module for future business workflow expansion.

## Consumer transaction boundary and delivery model

The consumer processing pipeline operates with idempotency and atomic transactional guarantees:

```text
Kafka message
  -> validation (TelemetryEventValidator, in KafkaTelemetryConsumer, before any DB work)
  -> database transaction begins (TelemetryService.recordTelemetry, @Transactional)
       -> check natural key deduplication (sensor_id, source_message_id), then event_id
       -> persist immutable telemetry (Flyway V2 unique constraints prevent duplicate insert)
       -> update machine latest-state projection (ordered by occurredAt, guarded by @Version)
       -> evaluate alerting rules (threshold checks, anti-spam deduplication, hysteresis auto-clearing)
       -> record Micrometer domain metrics (telemetry_ingested_total / telemetry_duplicates_total / telemetry_processing_time_seconds)
  -> database transaction commits
  -> Kafka message is acknowledged (at-least-once delivery satisfied)
```

The deduplication pre-check runs inside the transaction. Metrics are recorded in memory before the commit, so a transaction that rolls back at commit time (for example on a unique-constraint race) and is then retried can be counted more than once; the counters are operational signals, not an exact ledger.

Immutable telemetry persistence, latest-state projection, and alert evaluation share a single database consistency boundary: either all changes commit or none do. A Kafka acknowledgment is sent only after the database transaction commits.

Because network retries, QoS 1 delivery, or consumer rebalancing can re-deliver messages, at-least-once delivery is paired with application and database-level idempotency (Flyway V2 composite unique key on `sensor_id, source_message_id` and unique `event_id`). Stale concurrent writes are rejected via JPA `@Version` optimistic locking (Flyway V3, ADR-008). Poison pill messages bypass infinite retry loops and are routed directly to `telemetry-events-dlt` (ADR-006).

## Module boundaries and dependency direction

```text
REST / MQTT / Kafka adapters
            |
            v
Application use cases
            |
            v
Domain modules (machine, sensor, telemetry, state, alert, maintenance)
            |
            v
Persistence and messaging ports/adapters
```

Domain rules must not depend directly on HTTP, MQTT, Kafka, JPA, Redis, or Docker details. Boundary adapters validate/translate external representations; application use cases coordinate transactions and ports. Infrastructure implementations provide database and messaging access. Alert is implemented in Phase 4; maintenance/work-order modules remain a future boundary.

## Initial module/package structure

The initial root package is `com.industrialoperations.platform`. Organize by feature/domain rather than globally by technical layer:

```text
com.industrialoperations.platform
├── machine/       # machine domain, application use cases, REST/persistence adapters
├── sensor/        # sensor registration and platform-owned Sensor -> Machine configuration
├── telemetry/     # event contract, processing use cases, Kafka producer/consumer adapters, telemetry persistence
├── state/         # latest-state projection and query use cases
├── alert/         # alerting domain, evaluation service, anti-spam & hysteresis rules, REST adapter
├── ingestion/     # MQTT contract, validation, sensor lookup, event creation, Kafka handoff
└── common/        # narrowly scoped cross-cutting primitives; never a home for feature behavior
```

Within a feature, `domain`, `application`, and `infrastructure` subpackages may be used when they clarify ownership. REST adapters stay with their feature; MQTT stays in `ingestion`; Kafka adapters stay in `telemetry` unless a later extraction makes another home clearer.

Allowed dependency direction is adapters/infrastructure -> application -> domain. Feature dependencies follow machine <- sensor <- telemetry. `telemetry` depends on `state`, not the other way round: ADR-003 requires telemetry persistence and the latest-state projection to commit in one transaction, so telemetry invokes the projection synchronously. `state` owns the input contract of that call (`LatestStateUpdate`) and must not depend on telemetry's types or infrastructure. `telemetry` also invokes `alert` synchronously (`AlertService.evaluateTelemetry`) so that alert evaluation shares the same transaction (ADR-010); `alert` does not depend back on `telemetry`. `ingestion` may depend on the sensor lookup and telemetry publishing application contracts, but not on state or persistence implementations. Cross-feature interaction occurs through interfaces or small value types owned by the providing feature. `common` holds only behaviour-free primitives shared by more than one feature; `Measurements` lives there because the latest-state projection stores the same measurement set as telemetry history by definition. A type that acquires feature-specific rules leaves `common`. Dependencies must not point back upward or form cycles.

## Database schema & migration baseline

PostgreSQL schema evolution is managed via **Flyway** versioned SQL migrations (`backend/src/main/resources/db/migration/`):

| Migration | Version & File | Changes & Purpose |
| --- | --- | --- |
| **V1** | `V1__create_phase_1_baseline.sql` | Baseline tables: `machines`, `sensors`, `telemetry` (append-only history), and `machine_latest_state` (projection). |
| **V2** | `V2__add_telemetry_idempotency_constraints.sql` | Natural key deduplication: unique composite index `(sensor_id, source_message_id)` and unique constraint on `event_id` (ADR-005). |
| **V3** | `V3__add_machine_latest_state_version.sql` | Concurrency control: adds `version BIGINT NOT NULL DEFAULT 0` to `machine_latest_state` for JPA `@Version` optimistic locking (ADR-008). |
| **V4** | `V4__create_alerts_table.sql` | Alerting & Incident Management: creates `alerts` table with composite indexes `(machine_id, status)` and `(status)` (ADR-010). |
| **V5** | `V5__alerts_partial_unique_index.sql` | Check-then-insert isolation: partial unique index `idx_alerts_open_unique ON alerts (machine_id, rule_type) WHERE status IN ('ACTIVE', 'ACKNOWLEDGED')` (ADR-010). |

## REST API contract & Conventions

The REST API surface is located under `/api/v1`. Endpoints return standard HTTP status codes (`200 OK`, `201 Created`, `400 Bad Request`, `404 Not Found`, `409 Conflict`) with consistent JSON payloads. List endpoints currently return unpaginated collections ordered by timestamp descending; cursor/page-based pagination (`page`, `size`) is planned for Phase 5.

| Method and path | Purpose | Major request/response fields | Basic error cases |
| --- | --- | --- | --- |
| `POST /api/v1/machines` | Register a machine. | Request: `name`, optional `externalReference`. Response: `machineId`, `name`, `externalReference`, `createdAt`. | `400` invalid/missing fields; `409` duplicate external reference when supplied. |
| `GET /api/v1/machines/{machineId}` | Retrieve one machine. | Response: `machineId`, `name`, `externalReference`, `createdAt`. | `404` unknown machine. |
| `POST /api/v1/machines/{machineId}/sensors` | Register a sensor for a machine. | Request: stable `sensorId`, `name`, optional `type`. Response: `sensorId`, `machineId`, `name`, `type`, `createdAt`. | `400` invalid fields; `404` unknown machine; `409` duplicate sensor ID. |
| `GET /api/v1/machines/{machineId}/sensors` | Retrieve registered sensors for a machine. | Response: JSON array of `sensorId`, `machineId`, `name`, `type`, `createdAt`. | `404` unknown machine. |
| `GET /api/v1/machines/{machineId}/telemetry` | Retrieve telemetry history (newest `occurredAt` first). | Response: JSON array of `telemetryId`, `machineId`, `sensorId`, `eventId`, `sourceMessageId`, `occurredAt`, `receivedAt`, `temperature`, `vibration`. | `404` unknown machine. |
| `GET /api/v1/machines/{machineId}/latest-state` | Retrieve the current latest-state projection. | Response: `machineId`, `telemetryId`, `eventId`, `sourceMessageId`, `sensorId`, `occurredAt`, `receivedAt`, `temperature`, `vibration`. | `404` no telemetry-derived state (an unknown machine also returns `404`). |
| `GET /api/v1/alerts` | Retrieve alerts in status `ACTIVE` (acknowledged alerts are not included). | Response: list of alerts with `id`, `machineId`, `sensorId`, `ruleType`, `severity`, `status`, `currentValue`, `thresholdValue`, `message`, timestamps. | `200` OK. |
| `GET /api/v1/alerts/machines/{machineId}` | Retrieve all alerts for a specific machine. | Response: list of machine alerts ordered by `triggeredAt DESC`. | `200` OK. |
| `POST /api/v1/alerts/{alertId}/acknowledge` | Acknowledge an active alert. | Response: acknowledged alert entity with `acknowledgedAt` timestamp. | `404` unknown alert ID. |

## Alerting Engine & Hysteresis Auto-Clearing (Phase 4)

The alerting engine detects critical equipment anomalies in real time without operator alert fatigue or threshold flapping:

| Rule Type | Trigger Condition | Clear Condition (Hysteresis) | Severity | Target Metric |
|---|---|---|---|---|
| `HIGH_TEMPERATURE` | Temperature ≥ 90.0°C | Temperature < 85.0°C (5.0°C deadband) | `CRITICAL` | `measurements.temperature` |
| `HIGH_VIBRATION` | Vibration ≥ 0.80g | Vibration < 0.60g (0.20g deadband) | `CRITICAL` | `measurements.vibration` |

- **Open Alert Deduplication:** When a breach condition persists across continuous high-frequency telemetry messages, the system queries for existing open alerts (`ACTIVE` + `ACKNOWLEDGED`). If an open alert exists, it updates `current_value` in place instead of spawning duplicate database records. Acknowledging an alert does not open a second alert while the machine remains in breach.
- **Out-of-Order Telemetry Guard:** Telemetry is only evaluated for alerts if it updates or matches the machine's latest operational state projection. Delayed historical readings neither trigger false alerts nor auto-clear active incidents.
- **Hysteresis Noise Suppression:** Physical sensor readings naturally fluctuate around threshold boundaries due to thermal dissipation and measurement noise (e.g. oscillating between 89.9°C and 90.1°C). By enforcing a separate recovery threshold, rapid open/close cycling (threshold flapping) is completely eliminated.
- **Operator Lifecycle Transitions:** `ACTIVE` $\rightarrow$ `ACKNOWLEDGED` (via operator REST call to `/api/v1/alerts/{alertId}/acknowledge`) $\rightarrow$ `RESOLVED` (automatically when telemetry safely drops below the recovery threshold).
- **Concurrency & Partial Unique Index:** Guarded against race conditions via JPA `@Version` on `Alert` and Flyway V5 partial unique index `idx_alerts_open_unique ON alerts (machine_id, rule_type) WHERE status IN ('ACTIVE', 'ACKNOWLEDGED')`.

## Verification, Testing & Observability Baseline

The platform enforces verification across unit, integration, and operational tiers:

- **Unit Testing:** Unit tests cover MQTT validation, sensor-to-machine resolution, event envelope creation, and latest-state ordering rules.
- **Automated Verification Suite:** Ephemeral PostgreSQL and Kafka Testcontainers verify:
  - Database schema migrations and persistence constraints (Flyway V1–V5).
  - End-to-end ingestion adapter $\rightarrow$ Kafka $\rightarrow$ PostgreSQL telemetry processing (the test calls `MqttIngestionAdapter.handle(...)` directly; the MQTT broker transport and `MqttTelemetryClient` are covered by unit tests only, not by a Mosquitto container).
  - Consumer downtime buffering and catch-up on restart.
  - Poison pill routing to `telemetry-events-dlt` and header diagnostics (ADR-006).
  - PostgreSQL outage with telemetry already in Kafka: the consumer waits for the database instead of dead-lettering (`TelemetryDatabaseOutageIntegrationTest`, pauses the PostgreSQL container, ADR-006).
  - Controlled batch replay from DLT back into primary pipeline (ADR-007).
  - Optimistic locking collision rejection on `MachineLatestState` (ADR-008).
  - Stateful alerting lifecycle, anti-spam deduplication, out-of-order suppression, and hysteresis auto-clearing (`AlertIntegrationTest.java`, ADR-010).
  - Actuator `/actuator/health` and Prometheus metrics export at `/actuator/prometheus` (ADR-009).
- **Continuous Integration (GitHub Actions):** `.github/workflows/ci.yml` runs `./mvnw clean verify` with Testcontainers on every push and pull request.
- **Observability Stack:** Prometheus (:9090) scrapes the application every 5 seconds; Grafana (:3000) provides pre-provisioned dashboards for telemetry ingestion, duplicates, percentiles, and JVM memory.

## Architectural Decision Records (ADRs)

1. **[ADR-001: Modular Monolith](adr/ADR-001-modular-monolith.md)** — Cohesive domain modules inside a single deployable artifact.
2. **[ADR-002: MQTT for Ingress, Kafka for Internal Transport](adr/ADR-002-mqtt-and-kafka.md)** — Decoupled ingress and asynchronous streaming.
3. **[ADR-003: At-Least-Once Telemetry Processing](adr/ADR-003-at-least-once-processing.md)** — Atomic persistence and projection commit before Kafka ACK.
4. **[ADR-004: Kafka Event Contract and Partitioning](adr/ADR-004-kafka-event-contract-and-failure-classification.md)** — JSON envelope with `machineId` partition key.
5. **[ADR-005: Telemetry Idempotency and Deduplication](adr/ADR-005-telemetry-idempotency-and-deduplication.md)** — Natural key composite deduplication via Flyway V2.
6. **[ADR-006: Dead Letter Topic and Error Handling](adr/ADR-006-dead-letter-topic-and-retry-strategy.md)** — Quarantine poison pills in `telemetry-events-dlt`.
7. **[ADR-007: Controlled DLT Replay Service](adr/ADR-007-controlled-dlt-replay.md)** — Operator-initiated batch replay preventing recursive loops.
8. **[ADR-008: Optimistic Locking on Machine State](adr/ADR-008-optimistic-locking-and-concurrency.md)** — Concurrency safety using JPA `@Version` and Flyway V3.
9. **[ADR-009: Application Observability and Metrics](adr/ADR-009-metrics-and-observability.md)** — Prometheus pull scraping, Micrometer counters/timers, and Grafana dashboard.
10. **[ADR-010: Stateful Alerting Engine and Hysteresis](adr/ADR-010-alerting-engine-and-hysteresis.md)** — In-pipeline operational threshold evaluation, anti-spam row deduplication, noise suppression hysteresis deadbands, and operator lifecycle management.
11. **[ADR-011: Cloud Deployment Architecture & Container Orchestration](adr/ADR-011-cloud-deployment-and-containerization.md)** — Docker Compose on AWS EC2, self-contained multi-stage build, and dynamic public IP addressing.
12. **[ADR-012: Acknowledge MQTT Only After Kafka Publish Confirmation](adr/ADR-012-mqtt-ack-after-kafka-publish.md)** — `PUBACK` only after the Kafka ack, bounded publish and `max.block.ms` waits, and asynchronous MQTT subscribe to avoid a Paho callback-thread deadlock.
