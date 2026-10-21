# Industrial Operations Platform

[![CI Pipeline](https://github.com/MuhammedArdaZ/industrial-operations-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/MuhammedArdaZ/industrial-operations-platform/actions/workflows/ci.yml)
[![Deployment](https://img.shields.io/badge/AWS%20EC2-Live%20Deployment-232F3E?logo=amazon-aws)](docs/adr/ADR-011-cloud-deployment-and-containerization.md)
[![Grafana](https://img.shields.io/badge/Grafana-Live%20Dashboard-F46800?logo=grafana)](docs/adr/ADR-011-cloud-deployment-and-containerization.md)

Industrial Operations Platform is a modular-monolith backend designed for high-frequency industrial equipment monitoring, telemetry ingestion, operational alerting, and asset tracking. It demonstrates practical enterprise backend architecture and distributed-systems patterns without introducing premature microservices or operational overhead.

---

## 🏗️ Architectural Overview & Telemetry Pipeline

The platform ingests high-frequency telemetry from factory floor sensors via MQTT, provides an asynchronous and decoupled buffer via Apache Kafka, persists immutable historical facts alongside an optimized machine latest-state projection, and evaluates stateful operational threshold alerts with anti-spam deduplication and hysteresis auto-clearing in PostgreSQL.

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
│  (part of Spring Boot Backend)  │
└────────────────┬────────────────┘
                 │ Scrape (every 5s)
                 ▼
┌───────────────────┐     Query API     ┌─────────────────────────────┐
│ Prometheus (:9090)│ ────────────────> │  Grafana Dashboard (:3000)  │
└───────────────────┘                   └─────────────────────────────┘
```

### Key Architectural Decisions (ADRs)

- **[ADR-001: Modular Monolith](docs/adr/ADR-001-modular-monolith.md)** — One cohesive, deployable Spring Boot application with strict domain package boundaries. Extraction into independent microservices occurs only when concrete scaling or organizational needs emerge.
- **[ADR-002: MQTT for Ingress, Kafka for Internal Transport](docs/adr/ADR-002-mqtt-and-kafka.md)** — MQTT satisfies lightweight device-level communication, while Kafka decouples ingestion from persistence, enabling consumer groups, replayability, and decoupled consumption.
- **[ADR-003: At-Least-Once Telemetry Processing](docs/adr/ADR-003-at-least-once-processing.md)** — A database transaction ensures immutable telemetry persistence and the latest-state projection are strictly atomic before acknowledging Kafka messages.
- **[ADR-004: Kafka Event Contract and Failure Classification](docs/adr/ADR-004-kafka-event-contract-and-failure-classification.md)** — Language-neutral JSON on the wire with evolution governed by `schemaVersion`, `machineId` as the partition key, and an explicit split between permanently invalid messages and transient failures.
- **[ADR-005: Telemetry Idempotency and Deduplication](docs/adr/ADR-005-telemetry-idempotency-and-deduplication.md)** — Enforces idempotent ingestion using composite natural keys `(sensor_id, source_message_id)` and UUID `event_id` across Flyway V2 constraints, preventing history inflation from edge/broker duplicate deliveries.
- **[ADR-006: Dead Letter Topic and Consumer Retry Strategy](docs/adr/ADR-006-dead-letter-topic-and-retry-strategy.md)** — Implements `telemetry-events-dlt` with `DeadLetterPublishingRecoverer` and `FixedBackOff(1000L, 2L)` to quarantine unrecoverable poison pills without blocking Kafka partition consumption. Database connection failures are classified separately and retried with an exponential back-off until PostgreSQL returns, so an outage never dead-letters valid telemetry.
- **[ADR-007: Controlled DLT Replay Strategy](docs/adr/ADR-007-controlled-dlt-replay.md)** — Administrative operator-triggered batch replay mechanism to reprocess quarantined dead-letter messages safely while preventing recursive loop ping-pong hazards.
- **[ADR-008: Optimistic Locking and Concurrency Control](docs/adr/ADR-008-optimistic-locking-and-concurrency.md)** — Eliminates silent lost updates on machine state projection using JPA `@Version` and Flyway V3 column, rejecting pessimistic row locking to avoid database lock contention on the ingestion path.
- **[ADR-009: Observability and Metrics](docs/adr/ADR-009-metrics-and-observability.md)** — Pull-based metrics scraping via Prometheus, Micrometer domain counters/timers, Actuator health endpoints, and pre-provisioned Grafana dashboards.
- **[ADR-010: Stateful Alerting Engine and Hysteresis](docs/adr/ADR-010-alerting-engine-and-hysteresis.md)** — In-pipeline operational threshold evaluation, anti-spam alert row deduplication, noise suppression hysteresis deadbands (clearing at 85.0°C / 0.60g), and operator lifecycle management via Flyway V4 composite indexes and Flyway V5 partial unique index.
- **[ADR-011: Cloud Deployment Architecture & Container Orchestration](docs/adr/ADR-011-cloud-deployment-and-containerization.md)** — Production-like deployment on AWS EC2 (`m7i-flex.large`) using multi-stage Docker build and Docker Compose orchestration with dynamic public IP management.
- **[ADR-012: Acknowledge MQTT Only After Kafka Publish Confirmation](docs/adr/ADR-012-mqtt-ack-after-kafka-publish.md)** — Closes the ingress loss window: the MQTT `PUBACK` is sent only after Kafka acknowledges the record, with bounded waits (`KAFKA_PUBLISH_TIMEOUT`, `KAFKA_MAX_BLOCK_MS`) and an asynchronous MQTT subscribe that removes a Paho callback-thread deadlock on reconnect.

---

## 📦 Module Boundaries

The codebase is organized by business feature/domain rather than generic technical layers:

- `machine` — Industrial asset identity, metadata, and lifecycle management.
- `sensor` — Sensor registration and platform-authoritative sensor-to-machine mapping.
- `ingestion` — MQTT device boundary (Eclipse Paho), payload validation, and event wrapping.
- `telemetry` — Immutable event processing, Kafka producer/consumer adapters, historical storage, and DLT replay.
- `state` — Optimized latest-state projection derived from telemetry events.
- `alert` — Threshold anomaly detection, anti-spam deduplication, hysteresis auto-clearing, and incident lifecycle management.
- `common` — Narrowly scoped, cross-cutting primitives only.

---

## 🛠️ Tech Stack

- **Runtime & Language:** Java 25
- **Framework:** Spring Boot 3.5 (Spring MVC, Spring Data JPA, Spring Kafka)
- **Database & Migrations:** PostgreSQL 16+, Flyway Versioned Migrations (`V1 Baseline`, `V2 Idempotency Constraints`, `V3 Optimistic Locking Version`, `V4 Alerting Engine & Composite Indexes`, `V5 Open Alerts Partial Unique Index`)
- **Messaging & Ingress:** Eclipse Mosquitto (MQTT 3.1.1), Apache Kafka 3.7+
- **Observability & Metrics:** Spring Boot Actuator, Micrometer Prometheus, Prometheus v2.51, Grafana 10.4
- **Testing & Verification:** JUnit 5, AssertJ, Testcontainers (PostgreSQL, Kafka), Awaitility
- **CI/CD, Containerization & Tooling:** GitHub Actions, Docker, Docker Compose, Python 3 (Sensor Simulator)

---

## 🚀 Getting Started & Local Development

### Prerequisites

- JDK 25
- Maven 3.9+ (or use the included `./mvnw` wrapper)
- Docker & Docker Compose

---

### 1. Run Integration Tests (Isolated & Ephemeral)

The test suite leverages **Testcontainers** to spin up isolated PostgreSQL and Apache Kafka containers automatically. The suite covers asynchronous event streaming, idempotency, optimistic locking (@Version), DLT replay, and Prometheus actuator metrics:

```bash
cd backend
./mvnw clean verify
```

---

### 2. Running the Full End-to-End Pipeline Locally

You can spin up the full pipeline locally (PostgreSQL, Mosquitto MQTT broker, Apache Kafka, Spring Boot Backend, Prometheus, Grafana) in four simple steps:

#### Step A: Start Infrastructure, Backend & Observability Containers

From the project root, create your local environment file and set `GRAFANA_ADMIN_PASSWORD` (the `.env` file is git-ignored; Compose refuses to start if the variable is missing):

```bash
cp .env.example .env
docker compose up -d
```

This boots the entire operational stack:
- **PostgreSQL 16** on port `5432` (Flyway migrations `V1`–`V5` auto-applied)
- **Eclipse Mosquitto MQTT** on port `1883`
- **Apache Kafka (KRaft)** on port `9092` (internal container listener on `29092`)
- **Spring Boot Backend** on port `8080` (health at `/actuator/health`, metrics at `/actuator/prometheus`)
- **Prometheus** on port `9090` (scraping backend metrics every 5s)
- **Grafana** on port `3000` (pre-provisioned with Prometheus datasource & telemetry dashboard)

*(Optional: To run the Spring Boot backend locally from source instead of in Docker, run `docker compose up -d postgres mosquitto kafka prometheus grafana` and then `./mvnw spring-boot:run` in `backend/`).*

Ingress failure handling can be tuned with environment variables (ADR-012):

| Variable | Default | Purpose |
|---|---|---|
| `KAFKA_PUBLISH_TIMEOUT` | `10s` | How long ingestion waits for the Kafka acknowledgement before rejecting the MQTT message (no `PUBACK`, so the broker redelivers). |
| `KAFKA_MAX_BLOCK_MS` | `5000` | How long `KafkaProducer.send()` may block on metadata or a full buffer when Kafka is unreachable. |

#### Step B: Access Observability & Dashboards

- **Grafana Dashboard:** Open [`http://localhost:3000`](http://localhost:3000) (User `admin`, password from `GRAFANA_ADMIN_PASSWORD` in your `.env`).  
  Navigate to **Dashboards** → **Industrial Operations - Telemetry Dashboard** to observe live ingestion rates, duplicate drops, p95/p99 latency percentiles, and JVM health.
- **Prometheus Targets:** Open [`http://localhost:9090/targets`](http://localhost:9090/targets) to verify the `industrial-backend` scrape target is in state `UP`.

#### Step C: Run the Python Sensor Simulator

The simulator registers a machine/sensor via REST API and streams realistic telemetry through MQTT:

```bash
cd simulator
pip install -r requirements.txt
python3 sensor_simulator.py
```

*(Tip: To test chaos resilience and observe live duplicate counters in Grafana, run: `python3 sensor_simulator.py --interval 0.5 --burst-size 3 --chaos-duplicates 0.3`)*

Alternatively, run the simulator as a container (this is how the EC2 deployment runs it). It is behind a Compose profile, so a plain `docker compose up -d` does not start it:

```bash
docker compose --profile simulator up -d simulator
```

The simulator registers its machine and sensor through the REST API once at startup and does not retry. If the backend is not ready yet, registration fails and every message is rejected as "sensor is not registered"; restart the simulator container once `/actuator/health` reports `UP`.

#### Step D: Verify via REST API

Query the live machine latest-state projection (use the `machineId` printed in the sensor simulator console output; `jq` is optional for pretty-printing):

```bash
# Query latest operational state projection
curl -s http://localhost:8080/api/v1/machines/<MACHINE_ID>/latest-state | jq .

# List all currently active alerts
curl -s http://localhost:8080/api/v1/alerts | jq .

# List alert history for a specific machine
curl -s http://localhost:8080/api/v1/alerts/machines/<MACHINE_ID> | jq .

# Acknowledge an active alert
curl -s -X POST http://localhost:8080/api/v1/alerts/<ALERT_ID>/acknowledge | jq .
```

---

### 🌐 Live Cloud Deployment (AWS EC2)

The platform is deployed live on an **AWS EC2** instance (`m7i-flex.large`, 2 vCPU, 8 GB RAM, Ubuntu 24.04). In accordance with ADR-011, dynamic public addressing is used (Elastic IP is avoided to prevent idle billing charges when stopped):

| Service | Public Endpoint | Credentials / Purpose |
|---|---|---|
| **Spring Boot Actuator** | `http://<EC2_PUBLIC_IP>:8080/actuator/health` | Subsystem health status (`UP`) |
| **Prometheus Metrics** | `http://<EC2_PUBLIC_IP>:8080/actuator/prometheus` | Domain counters & latency percentiles |
| **Operational Alerts API** | `http://<EC2_PUBLIC_IP>:8080/api/v1/alerts` | Active threshold alerts |
| **Grafana Dashboard** | `http://<EC2_PUBLIC_IP>:3000` | Real-time telemetry dashboard (password set via `GRAFANA_ADMIN_PASSWORD` on the host, never committed) |

*(Note: Replace `<EC2_PUBLIC_IP>` with the current public IPv4 address of the EC2 instance.)*

The cloud deployment runs the complete stack via Docker Compose: Mosquitto MQTT, Kafka KRaft, PostgreSQL 16 (Flyway V1–V5), Spring Boot multi-stage build, Prometheus, Grafana, and the Python sensor simulator streaming live telemetry continuously.

---

## 🗺️ Engineering Roadmap

- [x] **Phase 1 — Working Event-Driven Core:** End-to-end telemetry pipeline (Simulator → MQTT → Kafka → PostgreSQL → REST API), domain modeling, Flyway baseline, and Testcontainers integration tests.
- [x] **Phase 2 — Distributed Systems Depth:**
  - [x] Idempotency & deduplication (`(sensor_id, source_message_id)`, Flyway V2, ADR-005)
  - [x] Kafka partition ordering strategy (`machineId` key, ADR-004)
  - [x] Dead Letter Topic & Poison Pill resilience (`telemetry-events-dlt`, `FixedBackOff`, ADR-006)
  - [x] Controlled replay of failed DLT events (`TelemetryDltReplayService`, ADR-007)
  - [x] Concurrency & optimistic locking (`@Version` on `MachineLatestState`, Flyway V3, ADR-008)
  - [x] MQTT acknowledgement only after Kafka publish confirmation, plus Paho reconnect deadlock fix (ADR-012)
  - [x] PostgreSQL outage holds valid telemetry in Kafka instead of dead-lettering it (exponential back-off for database connection failures, `TelemetryDatabaseOutageIntegrationTest`, ADR-006)
  - [x] Distributed failure matrix & recovery experiments (consumer downtime buffering, poison pill quarantine, out-of-order clock rejection, concurrent race tests; [docs/failure-scenarios.md](docs/failure-scenarios.md))
- [x] **Phase 3 — Production Readiness:**
  - [x] Full application containerization & Docker Compose orchestration (`backend/Dockerfile`, dual Kafka listeners)
  - [x] GitHub Actions CI pipeline with Testcontainers verification (`.github/workflows/ci.yml`)
  - [x] Prometheus metrics endpoint & Actuator health checks (`TelemetryMetrics`, Micrometer, `/actuator/prometheus`)
  - [x] Grafana dashboards for telemetry pipeline observability (`industrial-telemetry-dashboard.json`, Prometheus `:9090`, Grafana `:3000`)
- [x] **Phase 4 — Operational Alerting & Hysteresis Engine:**
  - [x] Composite stateful alerting table & indexes (`machine_id, status`, Flyway V4, ADR-010)
  - [x] Open-alert partial unique index for parallel replay concurrency (`idx_alerts_open_unique`, Flyway V5, ADR-010)
  - [x] Critical threshold evaluation (Temperature ≥ 90.0°C, Vibration ≥ 0.80g) with anti-spam row deduplication
  - [x] Noise suppression & hysteresis auto-clear deadbands (clearing when Temp < 85.0°C, Vib < 0.60g)
  - [x] Out-of-order telemetry suppression (guarded by machine latest-state occurredAt)
  - [x] REST API endpoints (`/api/v1/alerts`, `/api/v1/alerts/machines/{machineId}`, `/api/v1/alerts/{alertId}/acknowledge`)
  - [x] Full lifecycle integration testing (`ACTIVE` → `ACKNOWLEDGED` → `RESOLVED`, `AlertIntegrationTest`)
  - [x] Live cloud deployment to AWS EC2 (`<EC2_PUBLIC_IP>`) with multi-stage Docker build (ADR-011)

