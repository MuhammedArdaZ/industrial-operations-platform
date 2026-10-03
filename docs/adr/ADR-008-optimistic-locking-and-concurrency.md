# ADR-008: Optimistic Locking and Concurrency Control on Machine State

## Context

In our event-driven telemetry pipeline:
1. `telemetry` is an append-only, immutable historical log of sensor observations.
2. `machine_latest_state` is a projection table representing the single latest operational condition (temperature, vibration, timestamps) per machine.

Kafka partition keying by `machineId` (ADR-004) guarantees that messages for a given machine within the same consumer group are delivered in order to a single thread under normal circumstances.

However, concurrency conflicts can still emerge:
1. **Parallel Replay vs. Live Stream:** When `TelemetryDltReplayService` (ADR-007) replays historical messages while real-time sensor streams arrive simultaneously.
2. **Multi-Consumer / Multi-Process Scaling:** If consumer instances or external management processes scale horizontally or execute out-of-order writes.
3. **Lost Update Hazard:** Without concurrency control, two competing transactions could read the same initial state, evaluate their updates, and the slower/stale transaction could overwrite the newer projection state without detection.

## Decision

1. **Adopt JPA Optimistic Locking (`@Version`):**
   - Add a `version BIGINT NOT NULL DEFAULT 0` column to `machine_latest_state` via Flyway migration `V3__add_machine_latest_state_version.sql`.
   - Annotate the corresponding entity field with `@Version` in `MachineLatestState.java`.

2. **Reject Pessimistic Locking (`SELECT ... FOR UPDATE`):**
   - Pessimistic row locking induces database lock contention, reduces throughput on high-frequency telemetry ingestion, and increases the risk of deadlocks across concurrent transactions.
   - Optimistic locking assumes conflicts are rare, introduces zero lock overhead on reads, and delegates conflict detection to update time (`WHERE version = ?`).

3. **Conflict Handling:**
   - When a concurrent update occurs, Hibernate detects a row update count of 0 and throws `OptimisticLockException` (wrapped by Spring in `ObjectOptimisticLockingFailureException`).
   - The transaction rolls back cleanly, protecting against silent state corruption.
   - For idempotent telemetry consumers, a retry or acknowledging the prevailing latest state ensures eventual consistency.

## Consequences

- **Safety:** Eliminates silent lost updates and guarantees projection consistency under race conditions.
- **Performance:** Non-blocking read and write path; minimal overhead with a single integer check on `UPDATE`.
- **Flyway Evolution:** Existing records seamlessly initialize with `version = 0`.
