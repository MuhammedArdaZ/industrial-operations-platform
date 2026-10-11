# ADR-005: Telemetry Ingestion Idempotency and Deduplication Strategy

## Context

Phase 1 established an at-least-once telemetry pipeline (ADR-003) from MQTT ingestion through Kafka to PostgreSQL persistence. In any distributed system operating under at-least-once semantics, duplicate message delivery is guaranteed to occur under normal operating conditions:
1. **Edge/MQTT Redelivery:** MQTT QoS 1 republishes packets when acknowledgment (PUBACK) packets are delayed or dropped over cellular/industrial networks.
2. **Kafka Redelivery:** If a consumer crashes, restarts, or undergoes partition rebalancing after committing a database transaction but before committing its Kafka offset, Kafka redelivers the unacknowledged records upon restart.
3. **Producer Retries:** Transient broker disconnects or network timeouts can trigger Kafka producer retries.

In Phase 1, the `telemetry` table lacked unique constraints on message identifiers, meaning redelivered messages would insert duplicate rows into `telemetry` and potentially trigger redundant latest-state updates. Phase 2 requires an explicit, tested idempotency mechanism.

## Decision

1. **Idempotency Key Definition:**
   - The primary deduplication key is the natural composite tuple `(sensor_id, source_message_id)`. 
   - `source_message_id` is assigned at the edge (sensor or gateway). Because different physical devices might generate overlapping sequential identifiers, the source message identifier is scoped by `sensor_id`.
   - In addition, internal Kafka `event_id` is globally unique per generated event envelope.

2. **Database Schema Constraints (Flyway V2):**
   - Add a unique constraint `uq_telemetry_sensor_source` on `(sensor_id, source_message_id)` in table `telemetry`.
   - Add a unique constraint `uq_telemetry_event_id` on `event_id` in table `telemetry`.
   - Applied via Flyway migration `V2__add_telemetry_idempotency_constraints.sql`.

3. **Idempotent Consumer Processing & Pre-Check Filter:**
   - In `TelemetryService.recordTelemetry()`, check for duplicates before attempting persistence:
     1. First, check `telemetryRepository.existsBySensorIdAndSourceMessageId(sensorId, sourceMessageId)`. If present, increment `telemetry_duplicates_total` with tag `reason="source_message_id"`.
     2. Second, check `telemetryRepository.existsByEventId(eventId)`. If present, increment `telemetry_duplicates_total` with tag `reason="event_id"`.
   - If a duplicate is detected via pre-check:
     - Log an informative message indicating that a duplicate was detected and skipped.
     - Skip insertion into the `telemetry` table.
     - Skip updating the `machine_latest_state` projection.
     - Return the existing persisted entity cleanly.
     - The Kafka consumer commits the record offset cleanly without error. Duplicate delivery is normal distributed systems behavior and must **not** be routed to the Dead Letter Topic (DLT).

4. **Handling Concurrent Races & PostgreSQL Transaction Abort Semantics:**
   - In PostgreSQL, a unique constraint violation (SQLSTATE 23505) causes the database transaction block to immediately enter an aborted state (`current transaction is aborted, commands ignored until end of transaction block`). A Spring `@Transactional` method cannot simply catch `DataIntegrityViolationException` and continue executing SQL statements within that same aborted transaction.
   - Therefore, the application-level pre-check (`existsBy...`) serves as the primary filter for 99.9% of re-deliveries.
   - If two concurrent threads race to process the identical message simultaneously, the second thread hits the database unique constraint. The resulting `DataIntegrityViolationException` safely rolls back the entire transaction. Spring Kafka's error handler then initiates retry; upon retrying, the pre-check finds the row committed by the winning thread and resolves the duplicate idempotently.

5. **Out-of-Order Message Handling:**
   - The `machine_latest_state` projection strictly enforces `occurredAt > current_state.occurredAt`. An older event arriving after a newer one is recorded in historical `telemetry` (if unique) but does **not** overwrite the latest machine state or trigger operational alerts.

## Consequences

- Telemetry history cannot be corrupted or inflated by duplicate MQTT or Kafka deliveries.
- Consumer offset progression is preserved: duplicate messages are discarded safely without triggering unnecessary error-handling loops or false poison-pill alerts.
- A composite B-tree index on `(sensor_id, source_message_id)` introduces $O(\log n)$ lookup overhead on ingestion while guaranteeing mathematical uniqueness.

## Alternatives Considered

- **Dedicated Deduplication Table (e.g. `processed_messages` with TTL):** Rejected. The `telemetry` table already stores all historical messages durably. Introducing a separate table would double database write I/O per telemetry point without adding reliability.
- **Redis In-Memory Deduplication (SetNX):** Deferred. Redis would introduce cache synchronization risks, operational overhead, and potential data loss on Redis node restart unless backed by persistent store. PostgreSQL transactional constraints remain the single source of truth.
- **Throwing Exception on Duplicate (Routing to DLT):** Rejected. Duplicate delivery is normal distributed system behavior, not an operational failure or poison pill. Flooding the DLT with duplicates would obscure real software bugs or malformed payloads.
