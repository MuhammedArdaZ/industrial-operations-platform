# Distributed Systems Failure Scenarios & Recovery Matrix

This document records the empirical results of our distributed systems failure, resilience, and recovery experiments as specified in Phase 2 of `docs/development-plan.md`.

---

## Experiment 1: Consumer Downtime, Kafka Buffering & Catch-Up

- **Problem:** When downstream consumer nodes crash, restart, or scale (triggering consumer group partition rebalance), high-frequency MQTT/Kafka ingress continues streaming. Without durable buffering, telemetry would be dropped.
- **Solution:** Apache Kafka decouples ingress from persistence as a durable commit log. Uncommitted messages remain buffered on the partition with offsets intact.
- **Test / Experiment:** `shouldBufferMessagesDuringConsumerDowntimeAndCatchUpOnRestart` in `KafkaTelemetryPipelineIntegrationTest.java`:
  1. Consumer listener containers are stopped (`container.stop()`).
  2. 3 telemetry events are produced to `telemetry-events`.
  3. Database is verified to contain 0 records during downtime.
  4. Consumer is restarted (`container.start()`).
  5. `Awaitility` waits for consumer to rebalance and catch up.
- **Result:** **PASSED.** The consumer successfully resumed from its uncommitted offset, sequentially persisted all 3 buffered messages to PostgreSQL history, and updated `MachineLatestState` projection with the newest observation (`msg-down-3`) without message loss.

---

## Experiment 2: Poison Pill Messages & Schema Corruption

- **Problem:** Malformed JSON, corrupted schemas, or invalid fields (e.g. missing `machineId` or unsupported `eventType`) cause serialization/validation errors. In a standard consumer, an unhandled exception triggers endless retry loops that block partition consumption.
- **Solution:** Spring Kafka `DefaultErrorHandler` combined with `DeadLetterPublishingRecoverer` and `TelemetryEventValidator` (ADR-006). Non-retryable exceptions (`InvalidTelemetryEventException`) bypass backoff retries and are immediately routed to `telemetry-events-dlt` with error diagnostics in Kafka headers.
- **Test / Experiment:** `shouldRejectInvalidMessageAndSendToDLT` in `KafkaTelemetryPipelineIntegrationTest.java`:
  1. An event with `eventType = "INVALID_EVENT_TYPE"` is sent to `telemetry-events`.
  2. Consumer rejects the message and routes it to `telemetry-events-dlt`.
  3. DLT consumer verifies the quarantined message and checks `kafka_dlt-exception-cause-fqcn`.
- **Result:** **PASSED.** Poison pills are safely quarantined in DLT; healthy messages on the partition continue processing without delay.

---

## Experiment 3: Duplicate Telemetry Delivery (Idempotency)

- **Problem:** At-least-once delivery semantics in network boundaries (MQTT QoS 1 re-transmissions, Kafka consumer rebalance redeliveries) cause duplicate telemetry arrivals. Without deduplication, telemetry history inflates and analytical projections become distorted.
- **Solution:** Database-level natural composite key `(sensor_id, source_message_id)` and UUID `event_id` enforced via Flyway V2 constraints (ADR-005), backed by pre-insert `existsBySensorIdAndSourceMessageId` checks in `TelemetryService`.
- **Test / Experiment:** `shouldIgnoreDuplicateTelemetry` in `TelemetryPersistenceIntegrationTest.java`:
  1. Identical `(sensor_id, source_message_id)` sent multiple times.
  2. Pre-insert checks identify duplicate and short-circuit write.
- **Result:** **PASSED.** Only a single telemetry record is stored in history, projection remains consistent, and duplicate message returns existing entity without throwing an unhandled database error.

---

## Experiment 4: Out-of-Order Telemetry Arrival

- **Problem:** Asynchronous network transport or retransmissions can cause an older sensor measurement (e.g. 12:00:00) to arrive after a newer one (12:00:05). Overwriting the latest state with older data would corrupt operational monitoring.
- **Solution:** Logical timestamp validation in `MachineStateService`:
  ```java
  if (update.occurredAt().isAfter(state.getOccurredAt())) {
      state.update(...);
      machineLatestStateRepository.save(state);
  }
  ```
- **Test / Experiment:** `shouldIgnoreOutOfOrderTelemetryWhenUpdatingLatestState` in `TelemetryPersistenceIntegrationTest.java`:
  1. Send newer observation (14:00:00, 90.0°C).
  2. Send delayed older observation (13:00:00, 60.0°C).
- **Result:** **PASSED.** Both records are appended to historical `telemetry` table, but `MachineLatestState` projection strictly retains the 14:00:00 observation (90.0°C).

---

## Experiment 5: Concurrent Race Conditions & Lost Updates

- **Problem:** Multiple sensor streams, DLT replay jobs, or concurrent worker threads reading and writing state simultaneously can create Time-of-Check-to-Time-of-Use (TOCTOU) race conditions, causing a stale write to overwrite a newer state (Lost Update).
- **Solution:** JPA Optimistic Locking (`@Version` on `MachineLatestState`, Flyway V3, ADR-008). Rejects stale updates via atomic SQL `WHERE machine_id = ? AND version = ?`.
- **Test / Experiment:** `shouldThrowOptimisticLockExceptionOnConcurrentUpdate` in `MachineStateConcurrencyIntegrationTest.java`:
  1. Two separate in-memory references (`copy1` and `copy2`) loaded with `version = 0`.
  2. `copy1` is saved and committed $\rightarrow$ DB version advances to `1`.
  3. `copy2` attempts to save with stale version `0`.
- **Result:** **PASSED.** Hibernate rejects `copy2` with `ObjectOptimisticLockingFailureException`. The stale write is prevented, and Kafka consumer retry mechanism allows the transaction to re-read and reconcile.

---

## Experiment 6: Controlled DLT Replay & Loop Prevention

- **Problem:** Quarantined messages in DLT require operator-initiated reprocessing after bug fixes without causing recursive replay ping-pong loops.
- **Solution:** `TelemetryDltReplayService` (ADR-007) with isolated consumer group, transport header stripping, and operational replay count tracking.
- **Test / Experiment:** `shouldReplayMessagesFromDltAndProcessSuccessfully` in `KafkaTelemetryPipelineIntegrationTest.java`:
  1. Malformed or quarantined message placed in `telemetry-events-dlt`.
  2. `replay(maxRecords)` called.
  3. Message re-injected into primary topic and processed into PostgreSQL.
  4. Second replay call returns 0 (offset committed).
- **Result:** **PASSED.** Quarantined messages can be reprocessed safely on demand.

---

## Simulator Chaos Injection Controls

The Python sensor simulator (`simulator/sensor_simulator.py`) includes dedicated chaos injection flags:

| Flag | Parameter | Description |
|---|---|---|
| `--chaos-duplicates` | `0.0 - 1.0` (float) | Injects immediate duplicate MQTT transmissions with identical `sourceMessageId`. |
| `--chaos-malformed` | `0.0 - 1.0` (float) | Injects corrupted schema/missing field payloads (poison pills). |
| `--chaos-abnormal` | `0.0 - 1.0` (float) | Injects extreme temperature/vibration spikes (120-160°C, 0.3-0.85g). |
| `--burst-size` | `N` (int) | Emits bursts of $N$ messages per cycle to simulate traffic surges. |
