# Industrial Operations Platform

Industrial Operations Platform is a modular-monolith backend designed for high-throughput industrial equipment monitoring, telemetry ingestion, and asset tracking. It demonstrates practical enterprise backend architecture and distributed-systems patterns without introducing premature microservices or operational overhead.

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
│ (System of Record)│                   └─────────────────────────────┘
└─────────┬─────────┘
          │
          ▼
┌───────────────────┐
│     REST API      │ ───> Clients / Dashboard / Analytics
└───────────────────┘
```

### Key Architectural Decisions (ADRs)

- **[ADR-001: Modular Monolith](docs/adr/ADR-001-modular-monolith.md)** — One cohesive, deployable Spring Boot application with strict domain package boundaries. Extraction into independent microservices occurs only when concrete scaling or organizational needs emerge.
- **[ADR-002: MQTT for Ingress, Kafka for Internal Transport](docs/adr/ADR-002-mqtt-and-kafka.md)** — MQTT satisfies lightweight device-level communication, while Kafka decouples ingestion from persistence, enabling consumer groups, replayability, and backpressure handling.
- **[ADR-003: At-Least-Once Telemetry Processing](docs/adr/ADR-003-at-least-once-processing.md)** — A database transaction ensures immutable telemetry persistence and the latest-state projection are strictly atomic before acknowledging Kafka messages.
- **[ADR-004: Kafka Event Contract and Failure Classification](docs/adr/ADR-004-kafka-event-contract-and-failure-classification.md)** — Language-neutral JSON on the wire with evolution governed by `schemaVersion`, `machineId` as the partition key, and an explicit split between permanently invalid messages and transient failures.

---

## 📦 Module Boundaries

The codebase is organized by business feature/domain rather than generic technical layers:

- `machine` — Industrial asset identity, metadata, and lifecycle management.
- `sensor` — Sensor registration and platform-authoritative sensor-to-machine mapping.
- `ingestion` — MQTT device boundary, payload validation, and event wrapping.
- `telemetry` — Immutable event processing, Kafka producer/consumer adapters, and historical storage.
- `state` — Optimized latest-state projection derived from telemetry events.
- `common` — Narrowly scoped, cross-cutting primitives only.

---

## 🛠️ Tech Stack

- **Runtime & Language:** Java 25
- **Framework:** Spring Boot 3.5 (Spring MVC, Spring Data JPA, Spring Kafka)
- **Database & Migrations:** PostgreSQL 16+, Flyway Versioned Migrations
- **Messaging:** Eclipse Mosquitto (MQTT), Apache Kafka
- **Testing:** JUnit 5, AssertJ, Testcontainers (PostgreSQL, Kafka)
- **Containerization:** Docker

---

## 🚀 Getting Started & Local Development

### Prerequisites

- JDK 25
- Maven 3.9+
- Docker (for Testcontainers and integration test suite)

### 1. Build and Run Tests (with Testcontainers)

The integration test suite utilizes **Testcontainers** to spin up isolated, ephemeral PostgreSQL containers automatically:

```bash
cd backend
mvn clean verify
```

### 2. Running the Application Locally

The application requires a reachable PostgreSQL instance; the `postgres` profile is the default. Flyway applies `V1__create_phase_1_baseline.sql` on startup. Every setting has a local default, so plain `mvn spring-boot:run` works against a local PostgreSQL on port 5432:

```bash
mvn spring-boot:run
```

Override any of them through the environment:

```bash
export DATABASE_URL=jdbc:postgresql://localhost:5432/industrial_operations
export DATABASE_USERNAME=industrial_operations
export DATABASE_PASSWORD=industrial_operations
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
mvn spring-boot:run
```

The Kafka consumer starts with the application. Without a reachable broker the application still starts and serves REST traffic, but the telemetry listener logs connection failures until a broker is available.

---

## 🗺️ Engineering Roadmap

- **Phase 1 — Working Event-Driven Core:** End-to-end telemetry pipeline (Simulator $\rightarrow$ MQTT $\rightarrow$ Kafka $\rightarrow$ PostgreSQL $\rightarrow$ REST API), domain modeling, Flyway baseline, and Testcontainers test suite.
- **Phase 2 — Distributed Systems Depth:** Idempotency & deduplication, Kafka partition strategies & consumer groups, Dead Letter Queues (DLQ), controlled replay, concurrency control, and failure simulations.
- **Phase 3 — Production Readiness:** Docker Compose orchestration, GitHub Actions CI, Prometheus metrics, Grafana dashboards, and Actuator health checks.

