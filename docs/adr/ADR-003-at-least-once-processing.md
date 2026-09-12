# ADR-003: Begin with At-Least-Once Telemetry Processing

## Context

Phase 1 must make telemetry history and machine latest state mutually consistent in PostgreSQL while consuming Kafka messages. A consumer can successfully commit a database transaction but fail before acknowledging the Kafka message, so a message may be delivered again.

## Decision

Use the following intended Phase 1 ordering: validate the Kafka message; begin a database transaction; persist immutable telemetry; update the machine latest-state projection; commit the database transaction; then acknowledge the Kafka message. Treat telemetry persistence and latest-state projection as one database consistency boundary. Describe the delivery model as at-least-once, not end-to-end exactly-once.

Idempotent processing, retries, DLQ, replay, and comprehensive failure semantics are deferred to Phase 2, where each solution must be demonstrated by a documented experiment and test.

## Consequences

- Telemetry history and latest state do not diverge because of a partial database update.
- Duplicate delivery remains possible after failures and must be considered explicitly in Phase 2.
- The project does not make unsupported exactly-once claims.
- A narrow Phase 1 Testcontainers baseline verifies the critical telemetry path; Phase 3 expands coverage and runs it in CI.

## Alternatives considered

- **End-to-end exactly-once processing in Phase 1:** rejected because it would add complexity and require a rigorously verified scope beyond the initial vertical slice.
- **Acknowledge Kafka before committing the database transaction:** rejected because a subsequent failure could lose telemetry.
- **Persist telemetry and latest state in separate transactions:** rejected because a partial update could leave the latest-state projection inconsistent with durable telemetry.
