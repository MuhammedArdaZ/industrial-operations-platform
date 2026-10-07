# ADR-009: Application Observability and Metrics (Micrometer, Prometheus & Grafana)

## Context

In an event-driven industrial IoT platform ingesting high-frequency telemetry across distributed boundaries (MQTT broker -> Kafka -> Consumer -> PostgreSQL), operational visibility is critical to maintain system health, detect performance degradation, and diagnose failures:

1. **Ingestion Volume & Throughput:** Operators must monitor the rate of incoming telemetry messages across machines and sensors.
2. **Duplicate & Chaos Detection:** Due to network retries, QoS 1 re-transmissions, or consumer rebalancing, duplicate messages arrive. We need quantitative visibility into how many duplicate observations are safely filtered out, broken down by cause (`sensor_message_id` vs `event_id`).
3. **Pipeline Processing Latency:** Telemetry persistence and latest-state projection must be tracked with percentiles (p50, p95, p99) to identify slow database transactions or database lock contention before messages back up in Kafka.
4. **Infrastructure & JVM Health:** Standard runtime signals (heap memory usage, garbage collection pauses, thread counts, database connection pool exhaustion) must be exposed in a standardized format.

Pushing metrics from application threads to an external metrics service can add network latency to the ingestion hot-path or cause failures if the monitoring backend is unavailable.

## Decision

1. **Adopt Spring Boot Actuator and Micrometer:**
   - Use `spring-boot-starter-actuator` and `micrometer-registry-prometheus`.
   - Micrometer provides a vendor-neutral application instrumentation facade, decoupling domain logic from specific metrics backends.
   - Instrument custom domain metrics inside `TelemetryMetrics.java`:
     - `telemetry_ingested_total` (Counter): Incremented upon every successfully processed telemetry message.
     - `telemetry_duplicates_total` (Counter with `reason` tag): Incremented when duplicate telemetry is detected and short-circuited (`reason="sensor_message_id"` or `reason="event_id"`).
     - `telemetry_processing_duration_seconds` (Timer): Measures database persistence and state projection execution time, publishing p50, p95, and p99 percentiles.

2. **Pull-Based Metrics Scraping via Prometheus:**
   - Expose metrics at the standard `/actuator/prometheus` endpoint.
   - Run a standalone **Prometheus** container (port `9090`) configured to pull/scrape metrics from `http://backend:8080/actuator/prometheus` at 5-second intervals (`scrape_interval: 5s`).
   - Pull-based scraping guarantees that application throughput is never throttled by the monitoring system.

3. **Automated Dashboard Provisioning via Grafana:**
   - Run a standalone **Grafana** container (port `3000`) pre-provisioned via declarative YAML (`grafana/provisioning/datasources` and `grafana/provisioning/dashboards`).
   - Load `industrial-telemetry-dashboard.json` automatically on container boot, providing immediate real-time visualization of:
     - Total Ingested Telemetry (Gauge & Rate)
     - Total Deduplicated Messages (Counter & Breakdown by Reason)
     - Processing Latency Percentiles (p50, p95, p99)
     - JVM Memory Heap Usage & GC Activity

4. **Integration Testing & Health Verification:**
   - Include `TelemetryObservabilityIntegrationTest.java` verifying that `/actuator/health` returns `UP` and `/actuator/prometheus` serves all custom telemetry metrics in Prometheus exposition format.

## Consequences

- **Performance:** In-memory metric recording using non-blocking atomic counters and latency reservoirs introduces negligible CPU/memory overhead.
- **Portability & Standardization:** OpenMetrics/Prometheus format is universally supported across cloud providers, Kubernetes, and self-hosted monitoring stacks.
- **Operational Ergonomics:** Zero manual setup required for developers or operators; `docker compose up -d` immediately starts a fully connected Prometheus and Grafana instance with pre-configured dashboards.
