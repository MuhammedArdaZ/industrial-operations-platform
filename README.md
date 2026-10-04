# Industrial Operations Platform

Industrial Operations Platform is a modular-monolith backend designed for high-frequency industrial equipment monitoring, telemetry ingestion, and asset tracking. It demonstrates practical enterprise backend architecture and distributed-systems patterns without introducing premature microservices or operational overhead.

---

## 🏗️ Architectural Overview & Telemetry Pipeline

The platform ingests high-frequency telemetry from factory floor sensors via MQTT, provides an asynchronous and decoupled buffer via Apache Kafka, and persists immutable historical facts alongside an optimized machine latest-state projection in PostgreSQL.

```text
┌───────────────────┐       MQTT        ┌─────────────────────────────┐
│  Sensor Simulator │ ────────────────> │  Spring Boot MQTT Ingestion │
└───────────────────┘                   └──────────────┬──────────────┘
                                                       │ Kafka Event Envelope
                                                       ▼
┌───────────────────┐      Consumer     ┌─────────────────────────────┐
│    PostgreSQL     │ <──────────────── │   Kafka Telemetry Topic     │
│ (System of Record)│                   └──────────────┬──────────────┘
└─────────┬─────────┘                                  │ Poison Pill / Fatal Error
          │                                            ▼
          ▼                             ┌─────────────────────────────┐
┌───────────────────┐                   │  Dead Letter Topic (-dlt)   │
│     REST API      │                   └──────────────┬──────────────┘
└───────────────────┘                                  │
                                                       │ Controlled Replay (TelemetryDltReplayService)
                                                       ▼
                                        (Re-injected into Pipeline)
```

### Key Architectural Decisions (ADRs)

- **[ADR-001: Modular Monolith](docs/adr/ADR-001-modular-monolith.md)** — One cohesive, deployable Spring Boot application with strict domain package boundaries. Extraction into independent microservices occurs only when concrete scaling or organizational needs emerge.
- **[ADR-002: MQTT for Ingress, Kafka for Internal Transport](docs/adr/ADR-002-mqtt-and-kafka.md)** — MQTT satisfies lightweight device-level communication, while Kafka decouples ingestion from persistence, enabling consumer groups, replayability, and backpressure handling.
- **[ADR-003: At-Least-Once Telemetry Processing](docs/adr/ADR-003-at-least-once-processing.md)** — A database transaction ensures immutable telemetry persistence and the latest-state projection are strictly atomic before acknowledging Kafka messages.
- **[ADR-004: Kafka Event Contract and Failure Classification](docs/adr/ADR-004-kafka-event-contract-and-failure-classification.md)** — Language-neutral JSON on the wire with evolution governed by `schemaVersion`, `machineId` as the partition key, and an explicit split between permanently invalid messages and transient failures.
- **[ADR-005: Telemetry Idempotency and Deduplication](docs/adr/ADR-005-telemetry-idempotency-and-deduplication.md)** — Enforces idempotent ingestion using composite natural keys `(sensor_id, source_message_id)` and UUID `event_id` across Flyway V2 constraints, preventing history inflation from edge/broker duplicate deliveries.
- **[ADR-006: Dead Letter Topic and Consumer Retry Strategy](docs/adr/ADR-006-dead-letter-topic-and-retry-strategy.md)** — Implements `telemetry-events-dlt` with `DeadLetterPublishingRecoverer` and `FixedBackOff(1000L, 2L)` to quarantine unrecoverable poison pills without blocking Kafka partition consumption.
- **[ADR-007: Controlled DLT Replay Strategy](docs/adr/ADR-007-controlled-dlt-replay.md)** — Administrative operator-triggered batch replay mechanism to reprocess quarantined dead-letter messages safely while preventing recursive loop ping-pong hazards.
- **[ADR-008: Optimistic Locking and Concurrency Control](docs/adr/ADR-008-optimistic-locking-and-concurrency.md)** — Eliminates silent lost updates on machine state projection using JPA `@Version` and Flyway V3 column, rejecting pessimistic row locking to maintain high ingestion throughput.

---

## 📦 Module Boundaries

The codebase is organized by business feature/domain rather than generic technical layers:

- `machine` — Industrial asset identity, metadata, and lifecycle management.
- `sensor` — Sensor registration and platform-authoritative sensor-to-machine mapping.
- `ingestion` — MQTT device boundary (Eclipse Paho), payload validation, and event wrapping.
- `telemetry` — Immutable event processing, Kafka producer/consumer adapters, historical storage, and DLT replay.
- `state` — Optimized latest-state projection derived from telemetry events.
- `common` — Narrowly scoped, cross-cutting primitives only.

---

## 🛠️ Tech Stack

- **Runtime & Language:** Java 25
- **Framework:** Spring Boot 3.5 (Spring MVC, Spring Data JPA, Spring Kafka)
- **Database & Migrations:** PostgreSQL 16+, Flyway Versioned Migrations (`V1 Baseline`, `V2 Idempotency Constraints`, `V3 Optimistic Locking Version`)
- **Messaging & Ingress:** Eclipse Mosquitto (MQTT 3.1.1), Apache Kafka 3.7+
- **Testing & Verification:** JUnit 5, AssertJ, Testcontainers (PostgreSQL, Kafka), Awaitility
- **Containerization & Tooling:** Docker, Docker Compose, Python 3 (Sensor Simulator)

---

## 🚀 Getting Started & Local Development

### Prerequisites

- JDK 25
- Maven 3.9+
- Docker & Docker Compose

---

### 1. Run Integration Tests (Isolated & Ephemeral)

The test suite leverages **Testcontainers** to spin up isolated PostgreSQL and Apache Kafka containers automatically, verifying asynchronous event streaming, idempotency, and DLT routing with **Awaitility**:

```bash
cd backend
mvn clean verify
```

---

### 2. Running the Full End-to-End Pipeline Locally

You can spin up the full pipeline locally (PostgreSQL, Mosquitto MQTT broker, Apache Kafka) in four simple steps:

#### Step A: Start Infrastructure & Backend Containers

From the project root:

```bash
docker compose up -d
```

This boots the entire operational stack:
- **PostgreSQL 16** on port `5432` (Flyway migrations `V1`, `V2`, `V3` auto-applied)
- **Eclipse Mosquitto MQTT** on port `1883`
- **Apache Kafka (KRaft)** on port `9092` (internal container listener on `29092`)
- **Spring Boot Backend** on port `8080`

*(Optional: To run the Spring Boot backend locally from source instead of in Docker, run `docker compose up -d postgres mosquitto kafka` and then `mvn spring-boot:run` in `backend/`).*

#### Step C: Run the Python Sensor Simulator

The simulator registers a machine/sensor via REST API and streams realistic telemetry through MQTT:

```bash
cd simulator
pip install -r requirements.txt
python3 sensor_simulator.py
```

#### Step D: Verify via REST API

Query the live machine latest-state projection (use the `machineId` printed in the sensor simulator console output; `jq` is optional for pretty-printing):

```bash
curl -s http://localhost:8080/api/v1/machines/<MACHINE_ID>/latest-state | jq .
```

---

## 🗺️ Engineering Roadmap

- [x] **Phase 1 — Working Event-Driven Core:** End-to-end telemetry pipeline (Simulator → MQTT → Kafka → PostgreSQL → REST API), domain modeling, Flyway baseline, and Testcontainers integration tests.
- [x] **Phase 2 — Distributed Systems Depth:**
  - [x] Idempotency & deduplication (`(sensor_id, source_message_id)`, Flyway V2, ADR-005)
  - [x] Kafka partition ordering strategy (`machineId` key, ADR-004)
  - [x] Dead Letter Topic & Poison Pill resilience (`telemetry-events-dlt`, `FixedBackOff`, ADR-006)
  - [x] Controlled replay of failed DLT events (`TelemetryDltReplayService`, ADR-007)
  - [x] Concurrency & optimistic locking (`@Version` on `MachineLatestState`, Flyway V3, ADR-008)
- [ ] **Phase 3 — Production Readiness:**
  - [x] Full application containerization & Docker Compose orchestration (`backend/Dockerfile`, dual Kafka listeners)
  - [x] GitHub Actions CI pipeline with Testcontainers verification (`.github/workflows/ci.yml`)
  - [ ] Prometheus metrics endpoint & Actuator health checks
  - [ ] Grafana dashboards for telemetry pipeline observability
