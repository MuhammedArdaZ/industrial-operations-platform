# ADR-007: Controlled Dead Letter Topic Replay Strategy

## Context

Phase 2 established a Dead Letter Topic (`telemetry-events-dlt`) using `DeadLetterPublishingRecoverer` (ADR-006). When messages fail due to schema validation errors, transient network outages exceeding retry limits, or unparseable payloads, they are safely quarantined in the DLT to prevent pipeline blocking.

However, dead lettered messages cannot remain permanently inert. In real operations:
1. Operational bugs or database schemas are corrected.
2. Quarantined messages must be re-injected into the primary pipeline to restore state continuity without manual database surgery.
3. Automated infinite redelivery loops between the primary topic and DLT (the "ping-pong" anti-pattern) must be strictly prevented.

## Decision

1. **Administrative Controlled Replay (On-Demand):**
   - Replay is explicitly **operator-triggered**, never fully automatic or running in an uncontrolled infinite loop.
   - Implemented as `TelemetryDltReplayService` exposed via an administrative endpoint (`POST /api/v1/admin/telemetry/dlt/replay`) with a configurable `maxMessages` batch limit.

2. **Replay Flow:**
   - Dedicated replay consumer reads uncommitted records from `telemetry-events-dlt`.
   - Records are stripped of transport-level error headers (such as `kafka_dlt-exception-fqcn`) to avoid confusing downstream consumers.
   - An operational header `kafka_dlt-replay-count` is incremented.
   - The payload is re-published to the primary topic `telemetry-events` using `kafkaTemplate.send()`.
   - The offset in `telemetry-events-dlt` is committed upon successful republish.

3. **Loop Prevention (Poison Pill Safeguard):**
   - If a replayed message fails processing again in the primary consumer, it is routed back to the DLT by `DeadLetterPublishingRecoverer`.
   - If `kafka_dlt-replay-count` exceeds the maximum replay threshold (e.g. 3 attempts), the message is flagged as permanently unrecoverable and logged for manual operator triage, stopping recursive replay loops.

## Consequences

- Engineers can safely reprocess failed telemetry points after deploying fixes.
- Decoupling replay from real-time consumption ensures batch replay does not starve or overwhelm ongoing real-time ingestion.
- Offset tracking on the DLT ensures no message is replayed more than once per execution.
