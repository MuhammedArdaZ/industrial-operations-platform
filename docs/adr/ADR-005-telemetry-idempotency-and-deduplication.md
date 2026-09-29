# ADR-005: Telemetry Ingestion Idempotency and Deduplication Strategy

## Context

Phase 1 established an at-least-once telemetry pipeline (ADR-003) from MQTT ingestion through Kafka to PostgreSQL persistence. In any distributed system operating under at-least-once semantics, duplicate message delivery is guaranteed to occur under normal operating conditions:
1. **Edge/MQTT Redelivery:** MQTT QoS 1 republishes packets when acknowledgment (PUBACK) packets are delayed or dropped over cellular/industrial networks.
2. **Kafka Redelivery:** If a consumer crashes, restarts, or undergoes partition rebalancing after committing a database transaction but before committing its Kafka offset, Kafka redelivers the unacknowledged records upon restart.
3. **Producer Retries:** Transient broker disconnects can trigger Kafka producer retries.

In Phase 1, the `telemetry` table lacked unique constraints on message identifiers, meaning redelivered messages would insert duplicate rows into `telemetry` and potentially trigger redundant latest-state updates. Phase 2 requires an explicit, tested idempotency mechanism.

## Decision

1. **Idempotency Key Definition:**
   - The primary deduplication key is the tuple `(sensor_id, source_message_id)`. 
   - `source_message_id` is assigned at the edge (sensor or gateway). Because different physical devices might generate overlapping sequential identifiers, the source message identifier is scoped by `sensor_id`.
   - In addition, internal Kafka `event_id` is globally unique per generated event envelope.

2. **Database Schema Constraints:**
   - Add a unique constraint `uq_telemetry_sensor_source` on `(sensor_id, source_message_id)` in table `telemetry`.
   - Add a unique constraint `uq_telemetry_event_id` on `event_id` in table `telemetry`.
   - Applied via Flyway migration `V2__add_telemetry_idempotency_constraints.sql`.

3. **Idempotent Consumer Processing:**
   - In `TelemetryService.recordTelemetry()`, check for the existence of `(sensor_id, source_message_id)` before persisting.
   - If the message has already been processed:
     - Log an informative message indicating that a duplicate was detected and skipped.
     - Skip insertion into the `telemetry` table.
     - Skip updating the `machine_latest_state` projection.
     - Return the existing or empty record without error.
   - The Kafka consumer finishes cleanly and acknowledges (commits) the Kafka offset. Duplicate delivery must **not** throw an exception, retry, or be sent to a Dead Letter Queue (DLQ).
   - If a concurrent race condition bypasses the pre-check (e.g., across parallel workers), the database unique constraint enforces isolation. Any `DataIntegrityViolationException` resulting from a duplicate key collision is caught and handled idempotently as a duplicate event.

4. **Out-of-Order Message Handling:**
   - Telemetry events can arrive out of order due to network latency variations.
   - The `machine_latest_state` projection strictly enforces `occurredAt > current_state.occurredAt`. An older event arriving after a newer one is recorded in historical `telemetry` (if unique) but does **not** overwrite the latest machine state.

## Consequences

- Telemetry history cannot be corrupted or inflated by duplicate MQTT or Kafka deliveries.
- Consumer offset progression is preserved: duplicate messages are discarded safely without triggering unnecessary error-handling loops or false poison-pill alerts.
- A composite index on `(sensor_id, source_message_id)` slightly increases write latency on ingestion but provides $O(1)$ duplicate lookups.

## Alternatives Considered

- **Dedicated Deduplication Table (e.g. `processed_messages` with TTL):** Rejected. The `telemetry` table already stores all historical messages durably. Introducing a separate table would double database write I/O per telemetry point without adding reliability.
- **Redis In-Memory Deduplication (SetNX):** Rejected for Phase 2 baseline. Redis would introduce cache synchronization risks, operational overhead, and potential data loss on Redis node restart unless backed by persistent store. PostgreSQL transactional constraints remain the single source of truth.
- **Throwing Exception on Duplicate (Letting DLQ Catch It):** Rejected. Duplicate delivery is normal distributed system behavior, not an operational failure or poison pill. Flooding the DLQ with duplicates would obscure real software bugs or malformed payloads.
