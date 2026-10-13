# ADR-010: Stateful Alerting Engine, Anti-Spam Deduplication, and Hysteresis Auto-Clearing

## Context

In high-frequency industrial IoT monitoring, machinery continuously streams telemetry (e.g. 5–10 Hz per sensor). When operating parameters exceed safety limits, naive threshold detection systems introduce severe operational failure modes:

1. **Alert Flooding & Storage Bloat:** An overheated machine streaming 10 messages per second would generate 600 alert records and operator notifications every minute. This causes alert fatigue and degrades database performance.
2. **Threshold Flapping:** Physical measurements naturally fluctuate near threshold boundaries due to thermal dissipation and sensor noise (e.g., oscillating between 89.9°C and 90.1°C). A simple binary threshold trigger causes rapid cycling between triggered and cleared states, producing hundreds of false alarms.
3. **Operational Lifecycle Management:** Operators require a workflow to triage ongoing incidents (`ACTIVE` -> `ACKNOWLEDGED` -> `RESOLVED`) while ensuring that normalized conditions automatically resolve outstanding issues without manual operator overhead.
4. **Acknowledged Incident Continuity:** If an operator acknowledges an alert while the physical breach persists (e.g. machine remains at 95°C during inspection), the system must not reopen duplicate active alerts on subsequent telemetry observations.
5. **Out-of-Order Telemetry Hazards:** Delayed telemetry arriving late over asynchronous networks must not trigger retroactive alerts or auto-clear active fresh incidents.

## Decision

1. **In-Pipeline Synchronous Evaluation within Database Transaction:**
   - Integrate `AlertService.evaluateTelemetry` directly into `TelemetryService.recordTelemetry(...)`.
   - *Design Trade-off:* Calling `AlertService` directly from `TelemetryService` creates a domain dependency between telemetry processing and alerting. This trade-off is accepted deliberately: it ensures that telemetry persistence, latest-state projection, and alert evaluation share a single atomic database consistency boundary, eliminating the need for two-phase commit (2PC) or outbox pattern overhead.

2. **Guarded Alert Evaluation (Out-of-Order Suppression):**
   - Telemetry observations are only evaluated for alerts if they update or match the machine's latest operational state projection (`state.getOccurredAt().equals(command.occurredAt())`).
   - Delayed or out-of-order telemetry records are persisted to historical storage for traceability, but bypass alert evaluation. This prevents delayed historical spikes from opening false alerts and prevents delayed historical low values from prematurely auto-clearing active alerts.

3. **Stateful Anti-Spam Deduplication Across All Open Alerts:**
   - Deduplication queries inspect all open alerts (`ACTIVE` and `ACKNOWLEDGED`) via `findByMachineIdAndRuleTypeAndStatusIn(machineId, ruleType, [ACTIVE, ACKNOWLEDGED])`.
   - If an open alert exists for the `(machine_id, rule_type)` combination, `current_value` is updated in place on the existing entity.
   - Acknowledging an alert does not cause subsequent telemetry to spawn a second alert while the machine remains in breach.
   - Only the initial transition from normal operating conditions to a breach state creates a new `Alert` entity.

4. **Noise Suppression via Hysteresis Deadbands:**
   - Asymmetric trigger and recovery thresholds eliminate threshold flapping:
     - **High Temperature:** Trigger ≥ 90.0°C (Severity: `CRITICAL`). Clear < 85.0°C (Deadband: 5.0°C).
     - **High Vibration:** Trigger ≥ 0.80g (Severity: `CRITICAL`). Clear < 0.60g (Deadband: 0.20g).
   - When telemetry drops strictly below the clear threshold, all open alerts (`ACTIVE` or `ACKNOWLEDGED`) for that rule type automatically transition to `RESOLVED` with `resolved_at` recorded.

5. **Concurrency Control & Partial Unique Index:**
   - **JPA `@Version` on `Alert`:** Protects against lost updates when an operator acknowledgment (`/acknowledge`) races concurrently with automated recovery (`resolveOpenAlerts`). Hibernate aborts stale concurrent writes with `OptimisticLockException`.
   - **Partial Unique Index (Flyway V5):** To eliminate check-then-insert race conditions under parallel Kafka replay or multi-threaded ingestion, `V5__alerts_partial_unique_index.sql` enforces:
     ```sql
     CREATE UNIQUE INDEX idx_alerts_open_unique ON alerts (machine_id, rule_type) WHERE status IN ('ACTIVE', 'ACKNOWLEDGED');
     ```
     This guarantees at the PostgreSQL engine level that two concurrent transactions can never insert multiple open alerts for the same machine and rule type.

6. **Scope Boundaries for Phase 4:**
   - **Hardcoded Thresholds:** The thresholds (90.0°C / 0.80g) are hardcoded constants in `AlertService` for Phase 4. Machine- and sensor-specific dynamic threshold configuration is deferred to Phase 5.
   - **Severity Tiers:** `AlertSeverity` defines `WARNING` and `CRITICAL`. Phase 4 evaluates only the critical safety thresholds as `CRITICAL`; multi-tier warning thresholds are reserved for Phase 5.
   - **Authentication:** The REST endpoints (`/api/v1/alerts`, `/acknowledge`) are unauthenticated in this phase for local development and demonstration. Recording the operator identity (`acknowledged_by`) and RBAC protection are planned as Phase 5 security hardening.
   - **Alert key granularity:** Open alerts are keyed by `(machine_id, rule_type)` and the out-of-order guard compares against the machine-level latest state. This is correct for the current one-sensor-unit-per-machine setup. With several sensor units on one machine, readings from different units would share one alert and one hysteresis band (a hot unit and a cool unit could open and resolve the same alert alternately), so multi-unit machines require revisiting the key before they are supported.

## Alternatives Considered

- **External Complex Event Processing (CEP) Engine (e.g. Apache Flink, Esper):**
  - Deferred. Adding an external stream-processing cluster introduces immense operational overhead, networking hops, and deployment complexity that is unjustified for point-in-time threshold and deadband rules.
- **In-Memory Caching (e.g. ConcurrentHashMap / Redis):**
  - Deferred. In-memory state without durable database backing loses active alert state during service restarts, container rebalancing, or blue-green deployments, triggering duplicate alerts upon reboot.
- **Stateless Alert Publishing on Every Breach:**
  - Rejected. Floods downstream notification channels and rapidly inflates database storage with redundant breach records.

## Consequences

- **Operational Reliability:** Eliminates alert noise and threshold flapping, providing operators with a single, authoritative incident record per breach.
- **Performance:** B-tree indexed lookups on `(machine_id, status)` and partial unique index on `(machine_id, rule_type)` ensure negligible database overhead during ingestion.
- **Consistency:** Alert state remains atomically synchronized with telemetry persistence and machine state updates.
