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
   - Implemented as `TelemetryDltReplayService.replay(int maxRecords)` using a bounded batch limit. An authenticated admin REST endpoint is planned for Phase 5.

2. **Replay Flow:**
   - Dedicated replay consumer with isolated group `telemetry-dlt-replay-group` reads uncommitted records from `telemetry-events-dlt`.
   - The service performs a single `poll(3s)` and re-publishes up to `maxRecords` records to the primary topic `telemetry-events` using `kafkaTemplate.send()`, preserving the original key (`machineId`) so replayed events land on the same partition as live traffic for that machine.
   - If at least one record was sent, the consumer calls `commitSync()`.

3. **Loop Prevention & Message Eligibility:**
   - Replay is intended for messages quarantined due to transient infrastructure outages or software bugs that have been patched in downstream consumers.
   - Permanently malformed poison pills (e.g. invalid JSON, missing critical keys) should be purged or fixed prior to replay to prevent them from failing validation and immediately returning to the DLT.

## Consequences

- Engineers can safely reprocess failed telemetry points after deploying fixes.
- Decoupling replay from real-time consumption ensures batch replay does not starve or overwhelm ongoing real-time ingestion.
- Synchronous offset tracking on the DLT ensures no message is replayed more than once per execution.

## Known limitations (current implementation)

- **Commit is not tied to republish success.** `kafkaTemplate.send()` is asynchronous and its result is not awaited before `commitSync()`. If a republish fails, the DLT offset is still committed and that record is lost from the replay path.
- **`maxRecords` smaller than the polled batch skips records.** No-argument `commitSync()` commits the position of *every* record returned by `poll()`, including those beyond `maxRecords` that were never republished.
- **Single poll.** A fresh consumer group may still be joining when the 3-second poll ends, so a call can return `0` even though the DLT is non-empty.
- These gaps are tracked as follow-up work; until fixed, call `replay` with a `maxRecords` large enough to cover the whole poll and verify the result.
