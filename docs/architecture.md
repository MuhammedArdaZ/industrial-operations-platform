# Platform Architecture & System Design

## Architectural style

The system starts as a modular monolith: one Spring Boot deployment and one codebase, organized into cohesive domain/application modules. This keeps development, debugging, testing, and deployment straightforward while preserving boundaries that may justify extraction later.

No microservices are planned. A service is extracted only when concrete evidence—such as independently scaling a workload, an ownership boundary, reliability isolation, or deployment cadence—outweighs the added operational complexity. Alerts and maintenance/work orders remain planned domain modules for future business workflow expansion.

## Primary components

| Component | Responsibility | Role in Platform |
| --- | --- | --- |
| Sensor simulator | Development/test telemetry source | Python program publishing normal and chaos telemetry (duplicates, abnormal spikes, bursts) to MQTT |
| REST API | Management and query interface | Machines, sensors, telemetry history, latest state |
| MQTT ingestion adapter | External device boundary | Subscribes to telemetry, validates contract, resolves sensor-to-machine mapping, forwards accepted events |
| Kafka producer | Event handoff | Publishes validated telemetry envelopes partitioned by `machineId` to `telemetry-events` |
| Kafka consumer | Asynchronous processing | Consumes telemetry, evaluates deduplication, persists history, and updates latest-state projection |
| Dead Letter Topic & Replay | Failure quarantine & recovery | Quarantines poison pills in `telemetry-events-dlt` and supports controlled operator replay via `TelemetryDltReplayService` (ADR-006, ADR-007) |
| Application/domain modules | Business rules and use cases | Machine, sensor, telemetry, latest state, and metrics instrumentation |
| PostgreSQL | Durable system of record | Machine/sensor data, immutable deduplicated telemetry history, optimistic-locked latest-state projection |
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
          │ Read Latest State                   ┌─────────────────────────────┐
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

- **Source message identity (`sourceMessageId`)** is generated by the sensor/simulator for one telemetry observation and is preserved when that same source message is redelivered. It is the key metadata for Phase 2 duplicate and idempotency experiments; it is not yet used to deduplicate in Phase 1.
- **Internal event identity (`eventId`)** identifies the `TelemetryReceived` event created by the platform. It supports tracing between ingestion and Kafka, and is distinct from `sourceMessageId`; a redelivered source message can result in another internal event during Phase 1.
- **Event type (`eventType`)** identifies the semantic meaning of a message and makes future topic consumers safer to evolve.
- **Schema version (`schemaVersion`)** provides an explicit starting point for compatible schema evolution.
- **Source observation time (`occurredAt`)** is when the measurement occurred at the source and supports ordering and state-projection rules.
- **Platform receipt time (`receivedAt`)** is when the platform received the MQTT message, enabling ingestion-lag diagnostics independently of source time.
- **Sensor identity** identifies the producing device and is validated against platform-owned sensor configuration.
- **Machine identity** is the authoritative machine resolved by ingestion/application logic from the validated sensor relationship; it is not an arbitrary value copied from an MQTT producer.
- **Telemetry payload** contains the measured values while keeping transport metadata separate.

This contract is the basis for later Phase 2 work on idempotency, replay, DLQ handling, and schema evolution. It does not prescribe a serialization format or final field set yet.

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

The ingestion adapter validates topic format, payload shape, required fields, timestamp format, measurement types, topic/payload sensor agreement, and that the sensor exists and is associated with a machine. It records `receivedAt` using the platform clock, generates `eventId`, and publishes only a validated, resolved internal event to Kafka. Downstream unrecoverable poison pills and transient failures are handled via Dead Letter Topic (`telemetry-events-dlt`), `FixedBackOff(1000L, 2L)` retries, and controlled operator replay via `TelemetryDltReplayService` (ADR-006, ADR-007).

## Data and consistency model

- **Machine** is the managed industrial asset.
- **Sensor** belongs to a machine and identifies the origin of telemetry. A sensor is a multi-measurement unit: one observation carries the full measurement set the unit reports, so `sensors.type` describes the kind of unit rather than a single measured quantity. When a unit that also reports pressure is introduced, it reports pressure alongside the existing measurements under a new `schemaVersion`.
- **Telemetry** is an immutable observation associated with a sensor and authoritative machine. It retains `sourceMessageId`, `eventId`, `occurredAt`, and `receivedAt` for traceability and idempotency deduplication (ADR-005).
- **Machine latest state** is a separate, query-oriented projection derived from valid persisted telemetry. It is not the source of historical truth. Its ordering rule is strict: an observation replaces the stored state only when its `occurredAt` is strictly after the stored `occurredAt`. An older observation is ignored, and an observation with an identical `occurredAt` leaves the existing state in place, so the first arrival wins a tie. Concurrency race conditions are guarded by JPA `@Version` optimistic locking (ADR-008).
- **Alert** and **maintenance/work order** are reserved domain modules for future business scenarios.

## Consumer transaction boundary and delivery model

The consumer processing pipeline operates with idempotency and atomic transactional guarantees:

```text
Kafka message
  -> validation (TelemetryEventValidator)
  -> check natural key deduplication (sensor_id, source_message_id)
  -> database transaction begins
       -> persist immutable telemetry (Flyway V2 unique constraints prevent duplicate insert)
       -> update machine latest-state projection (ordered by occurredAt, guarded by @Version)
  -> database transaction commits
  -> Kafka message is acknowledged (at-least-once delivery satisfied)
  -> publish Micrometer domain metrics (telemetry_ingested_total / telemetry_duplicates_total)
```

Immutable telemetry persistence and latest-state projection share a single database consistency boundary: either both changes commit or neither does. A Kafka acknowledgment is sent only after the database transaction commits.

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

Domain rules must not depend directly on HTTP, MQTT, Kafka, JPA, Redis, or Docker details. Boundary adapters validate/translate external representations; application use cases coordinate transactions and ports. Infrastructure implementations provide database and messaging access. Alert and maintenance/work-order modules may be present as future boundaries but contain no Phase 1 behavior.

## Initial module/package structure

The initial root package is `com.industrialoperations.platform`. Organize by feature/domain rather than globally by technical layer:

```text
com.industrialoperations.platform
├── machine/       # machine domain, application use cases, REST/persistence adapters
├── sensor/        # sensor registration and platform-owned Sensor -> Machine configuration
├── telemetry/     # event contract, processing use cases, Kafka producer/consumer adapters, telemetry persistence
├── state/         # latest-state projection and query use cases
├── ingestion/     # MQTT contract, validation, sensor lookup, event creation, Kafka handoff
└── common/        # narrowly scoped cross-cutting primitives; never a home for feature behavior
```

Within a feature, `domain`, `application`, and `infrastructure` subpackages may be used when they clarify ownership. REST adapters stay with their feature; MQTT stays in `ingestion`; Kafka adapters stay in `telemetry` unless a later extraction makes another home clearer.

Allowed dependency direction is adapters/infrastructure -> application -> domain. Feature dependencies follow machine <- sensor <- telemetry. `telemetry` depends on `state`, not the other way round: ADR-003 requires telemetry persistence and the latest-state projection to commit in one transaction, so telemetry invokes the projection synchronously. `state` owns the input contract of that call (`LatestStateUpdate`) and must not depend on telemetry's types or infrastructure. `ingestion` may depend on the sensor lookup and telemetry publishing application contracts, but not on state or persistence implementations. Cross-feature interaction occurs through interfaces or small value types owned by the providing feature. `common` holds only behaviour-free primitives shared by more than one feature; `Measurements` lives there because the latest-state projection stores the same measurement set as telemetry history by definition. A type that acquires feature-specific rules leaves `common`. Dependencies must not point back upward or form cycles.

## Database schema & migration baseline

PostgreSQL schema evolution is managed via **Flyway** versioned SQL migrations (`backend/src/main/resources/db/migration/`):

| Migration | Version & File | Changes & Purpose |
| --- | --- | --- |
| **V1** | `V1__init_schema.sql` | Baseline tables: `machines`, `sensors`, `telemetry` (append-only history), and `machine_latest_state` (projection). |
| **V2** | `V2__telemetry_idempotency_constraints.sql` | Natural key deduplication: unique composite index `(sensor_id, source_message_id)` and unique constraint on `event_id` (ADR-005). |
| **V3** | `V3__add_machine_latest_state_version.sql` | Concurrency control: adds `version BIGINT NOT NULL DEFAULT 0` to `machine_latest_state` for JPA `@Version` optimistic locking (ADR-008). |

## REST API contract

The REST API surface is located under `/api/v1`:

| Method and path | Purpose | Major request/response fields | Basic error cases |
| --- | --- | --- | --- |
| `POST /api/v1/machines` | Register a machine. | Request: `name`, optional `externalReference`. Response: `machineId`, `name`, `externalReference`, `createdAt`. | `400` invalid/missing fields; `409` duplicate external reference when supplied. |
| `GET /api/v1/machines/{machineId}` | Retrieve one machine. | Response: `machineId`, `name`, `externalReference`, `createdAt`. | `404` unknown machine. |
| `POST /api/v1/machines/{machineId}/sensors` | Register a sensor for a machine. | Request: stable `sensorId`, `name`, optional `type`. Response: `sensorId`, `machineId`, `name`, `type`, `createdAt`. | `400` invalid fields; `404` unknown machine; `409` duplicate sensor ID. |
| `GET /api/v1/machines/{machineId}/sensors` | Retrieve registered sensors for a machine. | Response: machine ID and a list of `sensorId`, `name`, and `type`. | `404` unknown machine. |
| `GET /api/v1/machines/{machineId}/telemetry` | Retrieve telemetry history. | Response: machine ID and telemetry entries with `sensorId`, `sourceMessageId`, `eventId`, `occurredAt`, `receivedAt`, and measurements. | `404` unknown machine. |
| `GET /api/v1/machines/{machineId}/latest-state` | Retrieve the current latest-state projection. | Response: `machineId`, source `sensorId`, `sourceMessageId`, `eventId`, `occurredAt`, `receivedAt`, and measurements. | `404` unknown machine or no telemetry-derived state. |

## Verification, Testing & Observability Baseline

The platform enforces verification across unit, integration, and operational tiers:

- **Unit Testing:** Unit tests cover MQTT validation, sensor-to-machine resolution, event envelope creation, and latest-state ordering rules.
- **Automated Verification Suite:** Ephemeral PostgreSQL and Kafka Testcontainers verify:
  - Database schema migrations and persistence constraints.
  - End-to-end MQTT $\rightarrow$ Kafka $\rightarrow$ PostgreSQL telemetry processing.
  - Consumer downtime buffering and catch-up on restart.
  - Poison pill routing to `telemetry-events-dlt` and header diagnostics (ADR-006).
  - Controlled batch replay from DLT back into primary pipeline (ADR-007).
  - Optimistic locking collision rejection on `MachineLatestState` (ADR-008).
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
