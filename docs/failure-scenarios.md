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
- **Solution:** `TelemetryDltReplayService` (ADR-007) with isolated consumer group (`telemetry-dlt-replay-group`), synchronous commit, and batch execution limits.
- **Test / Experiment:** `shouldReplayMessagesFromDltAndProcessSuccessfully` in `KafkaTelemetryPipelineIntegrationTest.java`:
  1. Quarantined message (e.g. from a since-resolved schema bug) present in `telemetry-events-dlt`.
  2. `replay(maxRecords)` called with batch limit.
  3. Message re-injected into primary topic `telemetry-events` and processed into PostgreSQL.
  4. Second replay call returns 0 (offset committed).
- **Result:** **PASSED.** Quarantined messages can be reprocessed safely on demand.

---

## Experiment 7: Operational Threshold Alerting, Anti-Spam Deduplication & Hysteresis Auto-Clearing

- **Problem:** Continuous high-frequency telemetry (e.g. 10 msgs/sec per sensor) streaming from machinery exceeding safety limits causes severe alert fatigue and database bloat if every observation spawns a new alert row. Furthermore, readings fluctuating near the trigger threshold (e.g. oscillating between 89.9°C and 90.1°C due to thermal noise) cause alert flapping (rapid open/close cycling), triggering hundreds of false alarms.
- **Solution:** Stateful in-pipeline alert evaluation with open-alert deduplication (`ACTIVE` + `ACKNOWLEDGED`), partial unique index (Flyway V5), and hysteresis recovery deadbands (ADR-010):
  - In-place row updates on `ACTIVE` and `ACKNOWLEDGED` alerts when breach conditions persist, preserving a single actionable record.
  - Asymmetric recovery thresholds (85.0°C for temperature, 0.60g for vibration) suppressing noise near the boundary.
  - Automatic transition of open alerts to `RESOLVED` when telemetry safely normalizes.
  - Guarded alert evaluation: out-of-order telemetry observations are strictly bypassed during alert evaluation.
- **Test / Experiment:** `AlertIntegrationTest.java`:
  1. `shouldCreateAlertOnCriticalTemperature`: A single breach reading (95.0°C) creates one `ACTIVE` alert with severity `CRITICAL`.
  2. `shouldNotCreateDuplicateAlertIfAlreadyActive`: A subsequent higher breach reading (96.0°C) updates `current_value` on the existing alert without inserting a duplicate row.
  3. `shouldAutoClearAlertWhenTelemetryNormalizesWithHysteresis`: Telemetry inside the deadband (88.0°C) leaves the alert active; dropping below the clear threshold (84.0°C) automatically transitions the alert to `RESOLVED` with `resolved_at` populated.
  4. `shouldAcknowledgeAlert`: Operator REST call `/api/v1/alerts/{alertId}/acknowledge` transitions `ACTIVE` to `ACKNOWLEDGED`.
  5. `shouldNotCreateDuplicateAlertIfAlreadyAcknowledgedAndBreachContinues`: Acknowledged alert remains in `ACKNOWLEDGED` status and updates its `current_value` when breach continues, without opening a duplicate `ACTIVE` alert.
  6. `shouldIgnoreOutOfOrderTelemetryForAlertEvaluation`: Delayed out-of-order telemetry does not trigger retroactive alerts or auto-clear active fresh incidents.
- **Result:** **PASSED.** Verified in Testcontainers integration suite. Anti-spam deduplication prevents duplicate rows during prolonged breaches, hysteresis completely eliminates threshold flapping, and out-of-order data is safely filtered.

---

## Experiment 8: Kafka Outage During Ingestion (MQTT Acknowledgement Boundary)

- **Problem:** `KafkaTelemetryProducer.publish` returned without awaiting the `send(...)` future, so Paho sent `PUBACK` before Kafka had accepted the record. A Kafka outage meant acknowledged-but-lost telemetry.
- **Solution:** Await the Kafka acknowledgement with a bounded timeout (`KAFKA_PUBLISH_TIMEOUT`, default `10s`) and rethrow failures as `TelemetryPublishException`, so `messageArrived` fails, no `PUBACK` is sent, and Mosquitto redelivers to the persistent session after reconnect (ADR-012).
- **Test / Experiment:**
  - Unit: `KafkaTelemetryProducerTest` covers publishing with the `machineId` partition key, a broker failure (wrapped with the original cause preserved), a timeout, an interrupt (flag restored), and an ack that arrives within the configured timeout.
  - Manual, full Compose stack with the simulator running: confirm the Grafana `telemetry_ingested_total` rate is rising, stop the Kafka container mid-run, wait, then start it again.
- **Result, first run (FAILED to recover):** Messages were no longer acknowledged while Kafka was down, as intended. But after Kafka returned, ingestion never resumed: the MQTT client went through ~130 s connection-lost/reconnect cycles indefinitely. A thread dump showed a deadlock between Paho's own threads:
  - `MQTT Call` (callback thread) was in `connectComplete -> MqttClient.subscribe -> waitForCompletion`, waiting for `SUBACK`.
  - `MQTT Rec` (receiver thread) was in `CommsCallback.messageArrived`, blocked because the persistent-session backlog had filled Paho's inbound queue (10 messages), which only `MQTT Call` drains.
  - Neither thread could proceed; keepalive timeout broke the connection, and the reconnect repeated the same pattern. Kafka recovering did not help, because nothing was blocked on Kafka.
- **Fix:** Switch to `MqttAsyncClient` and subscribe with an `IMqttActionListener`, so `connectComplete` returns immediately (ADR-012).
- **Result after fix:** **PASSED.** Ingestion resumed within seconds of Kafka restarting. `telemetry_duplicates_total` rose by 4: records that had timed out while still in the producer buffer were delivered once Kafka returned, and Mosquitto also redelivered the unacknowledged MQTT messages. The `(sensor_id, source_message_id)` constraint (ADR-005) rejected the second copies, so no telemetry was lost or stored twice.

---

## Experiment 9: Unresponsive Kafka Broker & Producer `max.block.ms`

- **Problem:** `KafkaProducer.send()` blocks the caller (the Paho callback thread) while fetching topic metadata, for up to `max.block.ms` (default 60 s). This happens before a future exists, so the publish timeout cannot bound it.
- **Solution:** Set `max.block.ms` to `${KAFKA_MAX_BLOCK_MS:5000}`, bounding the worst-case stall per message to about `max.block.ms + publish-timeout` ≈ 15 s (ADR-012).
- **Test / Experiment:** Full Compose stack with the simulator running; `docker pause industrial-kafka`, observe the backend logs, then `docker unpause industrial-kafka`. (`docker pause` keeps the container in Docker DNS. A stopped container drops out of DNS, which is a different failure, see the notes below.)
- **Result:**
  - Before: each message failed with `Topic telemetry-events not present in metadata after 60000 ms`, giving a 61.3 s reject/reconnect cycle.
  - After: the same failure after 5000 ms, giving a 6.3 s cycle (the extra 1.3 s is Paho reconnecting). The `MQTT connection lost` log comes from the exception thrown in `messageArrived` (the intended rejection), not from keepalive.
  - After unpausing, ingestion resumed with **0 duplicates**. A metadata-phase failure never enters the producer buffer, so the MQTT redelivery is the only copy (compare Experiment 8, where buffered records produced 4 duplicates).

### Notes from Experiments 8 and 9

- `docker compose start/restart <service>` also starts the service's `depends_on` chain (simulator -> backend -> kafka). Use the plain `docker` CLI to break a single container.
- If the `kafka` hostname cannot be resolved at startup, the backend fails fast with `No resolvable bootstrap urls`. Compose defines no `restart:` policy, so it stays down until restarted manually.
- `MqttTelemetryClient` connects in `@PostConstruct`, so it starts processing the MQTT backlog about 40 s before the application reports `Started` (Spring logs singleton-lock warnings). Starting it on `ApplicationReadyEvent` or via `SmartLifecycle` would fix this.
- `KafkaAdmin` topic creation adds about 40 s to startup when the broker is unresponsive.

---

## Experiment 10: PostgreSQL Outage & Consumer Retry Classification

- **Problem:** `DefaultErrorHandler` treated every failure except `InvalidTelemetryEventException` the same way: `FixedBackOff(1000L, 2L)`, then `telemetry-events-dlt`. A database outage is not the message's fault, but valid telemetry already in Kafka was dead-lettered, needed manual replay, and broke per-machine ordering (later readings were processed before the replayed one).
- **Solution:** Classify transient database failures (`CannotCreateTransactionException`, `DataAccessResourceFailureException`) separately with `DefaultErrorHandler.setBackOffFunction`: exponential back-off (1 s, ×2, capped at 30 s per interval, no total limit), never dead-lettered. The consumer holds its partition position and resumes on its own when PostgreSQL returns (ADR-006).

### 10a: Outage with live MQTT traffic

- **Test / Experiment:** Full Compose stack with the simulator running (`docker compose --profile simulator up -d`); `docker pause industrial-postgres` for 73 s, then `docker unpause industrial-postgres`.
- **Result:** `telemetry-events-dlt` stayed at offset 0. The failure surfaced on the Paho callback thread, not the consumer: `MqttIngestionAdapter` resolves the machine through `SensorService.findSensor`, which needs the database. After Hikari's 30 s `connectionTimeout` it failed with `CannotCreateTransactionException`, so no `PUBACK` was sent and Mosquitto kept the message for redelivery (ADR-012). Nothing new reached Kafka, so the consumer had nothing to dead-letter.
- **Takeaway:** this protection is accidental. It only exists because the ingestion lookup hits the database. Caching sensor configuration would remove it, and records already in Kafka (consumer lag, a backlog after a Kafka outage, a consumer restart) are not protected at all. 10b tests that case.

### 10b: Outage with records already in Kafka

- **Test / Experiment:** Same stack; `docker pause industrial-postgres`, then one existing event was re-produced directly to `telemetry-events` with `kafka-console-producer.sh` (bypassing MQTT). Automated as `TelemetryDatabaseOutageIntegrationTest`, which pauses the Testcontainers PostgreSQL container, sends a valid event, asserts the DLT stays empty for a 15 s outage, then asserts the telemetry is persisted after unpausing. The test lowers Hikari `connection-timeout` to 1 s and sets pgjdbc `socketTimeout` to 2 s so it fails fast.
- **Result before fix (FAILED):** the consumer thread failed three times, each after the 30 s Hikari `connectionTimeout` (13:35:46, 13:36:17, 13:36:48), and the record reached `telemetry-events-dlt` (offset 0 → 1) about 92 s after the first attempt: 3 attempts × 30 s + 2 × 1 s back-off. The earlier claim that an outage "longer than 2 seconds" dead-letters messages ignored the connection timeout, which dominates. In the integration test, the same record was dead-lettered within about 5 s.
- **Result after fix (integration test, PASSED):** retries continued at growing intervals (about 1, 2, 4 and 8 s between attempts, plus the 1 s connection timeout), the DLT stayed empty, and the telemetry was written once PostgreSQL returned. Both classified exception shapes occurred in one run: the first attempt used a pooled connection and failed mid-query (`SQLState 08006`, translated to `DataAccessResourceFailureException`); later attempts failed while opening the transaction (`SQLState 08003`, `CannotCreateTransactionException`).

### Notes from Experiment 10

- Each retry is a new `poll()` (the error handler seeks back to the failed record), so unlimited retries do not exceed `max.poll.interval.ms` (300 s). What must stay below it is one attempt plus one back-off interval: about 30 s + 30 s in production. Without the 30 s `maxInterval` cap, exponential growth would eventually get the consumer evicted from the group.
- A single listener thread consumes all three partitions, so an outage stops all of them. This is acceptable because every partition writes to the same PostgreSQL instance; a partition-specific failure would need a different strategy.
- The classification is deliberately narrow. Treating a permanent error as transient would retry it forever and stall the consumer, which is worse than dead-lettering a valid record that replay can recover.
- Production sets no pgjdbc `socketTimeout`. A paused or partitioned database never closes its sockets, so a query already running on a borrowed connection can hang indefinitely instead of failing (see Known Gaps).

---

## Simulator Chaos Injection Controls

The Python sensor simulator (`simulator/sensor_simulator.py`) includes dedicated chaos injection flags:

| Flag | Parameter | Description |
|---|---|---|
| `--chaos-duplicates` | `0.0 - 1.0` (float) | Injects immediate duplicate MQTT transmissions with identical `sourceMessageId`. |
| `--chaos-malformed` | `0.0 - 1.0` (float) | Injects payloads with the `measurements` block missing. These are rejected and discarded by `MqttIngestionAdapter` at the MQTT boundary, so they exercise ingestion validation, not the Kafka DLT (DLT routing is exercised by `shouldRejectInvalidMessageAndSendToDLT`, which writes directly to Kafka). |
| `--chaos-abnormal` | `0.0 - 1.0` (float) | Injects extreme temperature/vibration spikes (120-160°C, 0.85-1.85g) reliably breaching alert thresholds. |
| `--burst-size` | `N` (int) | Emits bursts of $N$ messages per cycle to simulate traffic surges. |

---

## Known Distributed Resilience Boundaries & Disaster Recovery Gaps

1. **Ingress Boundary & MQTT Publisher Failure:**
   - Under Eclipse Paho MQTT QoS 1, the client sends `PUBACK` when `messageArrived` returns normally. `MqttTelemetryClient` rethrows unexpected runtime failures so the message is not acknowledged (Paho drops the connection and the broker redelivers to the persistent session, `cleanSession=false`).
   - **Resolved (ADR-012):** `KafkaTelemetryProducer.publish` now waits for the Kafka acknowledgement (`KAFKA_PUBLISH_TIMEOUT`, default `10s`) and `send()` blocks for at most `KAFKA_MAX_BLOCK_MS` (default `5000`), so `PUBACK` is sent only after Kafka has accepted the record. Previously the send future was not awaited and a Kafka outage lost acknowledged telemetry (Experiments 8 and 9).
   - **Remaining limits:** messages are processed one at a time on the Paho callback thread, so throughput is bounded by publish latency (manual acknowledgements are the scaling path). Mosquitto runs without persistence, so a Mosquitto restart during a Kafka outage loses the unacknowledged backlog.
   - Kafka producer uses `acks=all` and default Kafka 3.x producer idempotence (`enable.idempotence=true`).
   - Invalid MQTT messages (bad topic, malformed JSON, missing fields, unregistered sensor) are logged and discarded at the ingestion boundary. They never reach Kafka or the DLT and are not counted by a metric.
2. **Infrastructure Outage vs. DLT Overflow:**
   - **Resolved (ADR-006, Experiment 10):** transient database failures (`CannotCreateTransactionException`, `DataAccessResourceFailureException`) are retried with an exponential back-off capped at 30 s per interval and are never dead-lettered. Previously, with `FixedBackOff(1000L, 2L)`, a PostgreSQL outage longer than about 92 s (three 30 s connection timeouts plus back-off) routed valid telemetry already in Kafka to `telemetry-events-dlt`.
   - **Remaining limits:** no pgjdbc `socketTimeout` is configured, so a query in flight when the database freezes can block the consumer thread indefinitely. Other unclassified failures still go to the DLT after two retries.
3. **Security & Access Control:**
   - DLT replay and alert acknowledgment endpoints are currently unauthenticated in this demonstration release. Production deployments require JWT authentication, role-based access control (RBAC), and recording `acknowledged_by` operator identities.
