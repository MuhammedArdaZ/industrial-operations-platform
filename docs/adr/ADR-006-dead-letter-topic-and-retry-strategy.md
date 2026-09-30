# ADR-006: Dead Letter Topic and Consumer Retry Strategy

## Context

In Phase 1, consumer failure classification was introduced (ADR-004):
- Failures were partitioned into permanent (validation, deserialization) and transient (database unavailability, network hiccups).
- In Phase 1, permanently invalid messages were logged and discarded, and transient failures kept default framework retry loops.

Discarding permanently invalid messages is unacceptable in production because:
1. Malformed messages or schema discrepancies cannot be inspected after the fact.
2. Silent data loss impairs auditing, diagnostics, and root-cause analysis on IoT sensor anomalies.
3. Unhandled poison pills or infinite retry loops can block an entire Kafka partition, causing lag to accumulate for all subsequent messages for that machine.

## Decision

1. **Dead Letter Topic (DLT) Provisioning:**
   - Establish a dedicated Dead Letter Topic following Spring Kafka 3 conventions: `<original-topic>-dlt` (i.e. `telemetry-events-dlt`).
   - Provisioned via `KafkaTopicConfig` with the same partition count (3 partitions) and replication factor (1) as the primary topic.

2. **Error Recovery Mechanism (`DeadLetterPublishingRecoverer`):**
   - Configure a `DeadLetterPublishingRecoverer` wired to `KafkaOperations<Object, Object>` / `KafkaTemplate`.
   - When a record fails terminal processing, the recoverer publishes the original record payload and headers to `telemetry-events-dlt`.
   - Standard Kafka headers are appended by the recoverer:
     - `kafka_dlt-original-topic`
     - `kafka_dlt-original-partition`
     - `kafka_dlt-original-offset`
     - `kafka_dlt-exception-fqcn`
     - `kafka_dlt-exception-message`
     - `kafka_dlt-exception-stacktrace`

3. **Retry Policy and Backoff (`DefaultErrorHandler`):**
   - **Non-retryable exceptions (Immediate DLT dispatch with 0 retries):**
     - `InvalidTelemetryEventException`: Permanent payload validation errors (e.g. unknown `eventType`, negative/invalid coordinates, missing required envelope attributes).
     - Deserialization exceptions (`DeserializationException`): Malformed JSON, unparseable byte streams.
     - `MessageConversionException`: Data type binding failures.
   - **Retryable exceptions (Transient):**
     - Transient database errors, lock acquisition timeouts, broker disconnects.
     - Retry with fixed backoff: 3 attempts with 1-second interval (`FixedBackOff(1000L, 2L)` - initial attempt + 2 retries).
     - If all retry attempts are exhausted, publish to `telemetry-events.DLT`.

4. **Offset Commit Semantics:**
   - Once a failed record is successfully routed to the Dead Letter Topic by `DeadLetterPublishingRecoverer`, the original offset in the primary topic is committed (ACKed).
   - The consumer partition advances to the next offset without stalling the pipeline.

## Consequences

- Poison pills no longer stall partition consumption; telemetry for healthy sensors on the same partition continues processing unimpeded.
- Corrupted messages are preserved durably in Kafka for operator inspection, triage, and subsequent controlled replay.
- Network overhead: publishing to DLT consumes broker bandwidth and disk storage. Retention on DLT topics must be managed in operations.

## Alternatives Considered

- **Logging and discarding (Phase 1 baseline):** Rejected. Causes unrecoverable telemetry loss and blinds operational teams to faulty sensors or breaking changes.
- **Retrying indefinitely until manual fix:** Rejected. Completely blocks the partition (Head-of-Line blocking), accumulating massive Kafka lag for all machines hashed to that partition.
