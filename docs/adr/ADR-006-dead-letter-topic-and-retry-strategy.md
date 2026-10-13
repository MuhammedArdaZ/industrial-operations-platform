# ADR-006: Dead Letter Topic and Consumer Retry Strategy

## Context

In Phase 1, consumer failure classification was introduced (ADR-004):
- Failures were partitioned into permanent (validation, deserialization) and transient (database unavailability, network hiccups).
- In Phase 1, permanently invalid messages were logged and discarded, and transient failures kept default framework retry loops.

Discarding permanently invalid messages is unacceptable in production because:
1. Malformed messages or schema discrepancies cannot be inspected after the fact.
2. Silent data loss impairs auditing, diagnostics, and root-cause analysis on IoT sensor anomalies.
3. Unhandled poison pills or infinite retry loops block an entire Kafka partition (Head-of-Line blocking), causing lag to accumulate for all subsequent messages for that machine.

## Decision

1. **Dead Letter Topic (DLT) Provisioning:**
   - Establish a dedicated Dead Letter Topic following platform kebab-case conventions: `telemetry-events-dlt`.
   - Provisioned with the same partition count (3 partitions) and replication factor (1) as the primary topic.
   - *Destination Resolver:* No custom resolver is configured. In Spring Kafka 3.x the default `DeadLetterPublishingRecoverer` resolver is `(record, exception) -> new TopicPartition(record.topic() + "-dlt", record.partition())`, which already yields the lowercase hyphenated name `telemetry-events-dlt` and preserves the original partition. (Spring Kafka 2.x used the `.DLT` suffix; pin the resolver explicitly if a future upgrade changes the default.)

2. **Error Recovery Mechanism (`DeadLetterPublishingRecoverer`):**
   - Configure a `DeadLetterPublishingRecoverer` wired to `KafkaTemplate<Object, Object>`.
   - When a record fails terminal processing, the recoverer publishes the original record payload and headers to `telemetry-events-dlt`.
   - Standard diagnostic Kafka headers are appended by the recoverer:
     - `kafka_dlt-original-topic`: Topic of origin.
     - `kafka_dlt-original-partition`: Partition of origin.
     - `kafka_dlt-original-offset`: Offset where failure occurred.
     - `kafka_dlt-exception-fqcn`: Top-level exception class (e.g. `ListenerExecutionFailedException`).
     - `kafka_dlt-exception-cause-fqcn`: Root-cause exception class (e.g. `InvalidTelemetryEventException`).
     - `kafka_dlt-exception-message`: Detailed exception error message.
     - `kafka_dlt-exception-stacktrace`: Stacktrace at point of failure.

3. **Retry Policy and Backoff (`DefaultErrorHandler`):**
   - **Non-retryable exceptions (Immediate DLT dispatch with 0 retries):**
     - `InvalidTelemetryEventException`: Permanent payload validation errors (e.g. unknown `eventType`, unsupported `schemaVersion`, missing required envelope attributes or measurements). Value-range checks (e.g. physically impossible readings) are not implemented..
     - Deserialization exceptions (`DeserializationException`): Malformed JSON, unparseable byte streams.
     - `MessageConversionException`: Data type binding failures.
   - **Retryable exceptions (Transient):**
     - Transient database errors, lock acquisition timeouts, broker disconnects.
     - Retry with fixed backoff: 3 attempts with 1-second interval (`FixedBackOff(1000L, 2L)` - initial attempt + 2 retries).
     - If all retry attempts are exhausted, publish to `telemetry-events-dlt`.

4. **Transient Outage vs. DLT Quarantine Trade-Off:**
   - With `FixedBackOff(1000L, 2L)`, total retry duration is approximately 2 seconds. If a database outage exceeds 2 seconds, valid telemetry messages will exhaust retries and be routed to `telemetry-events-dlt`.
   - While DLT preserves messages durably and prevents data loss (allowing subsequent replay via `TelemetryDltReplayService`), DLT was conceptually designed for unrecoverable poison pills rather than infrastructure outages. In a high-throughput production environment, an exponential backoff with Kafka listener container pausing (`CommonContainerStoppingErrorHandler` or pausing the consumer partition) is recommended to halt partition consumption during prolonged infrastructure downtime instead of filling DLT with valid traffic.

5. **Offset Commit Semantics:**
   - Once a failed record is successfully routed to the Dead Letter Topic by `DeadLetterPublishingRecoverer`, the original offset in the primary topic is committed (ACKed).
   - The consumer partition advances to the next offset without stalling the pipeline.

## Consequences

- Poison pills no longer stall partition consumption; telemetry for healthy sensors on the same partition continues processing unimpeded.
- Corrupted messages are preserved durably in Kafka for operator inspection, triage, and subsequent controlled replay.
- Network overhead: publishing to DLT consumes broker bandwidth and disk storage. Retention on DLT topics (`log.retention.hours=168`) must be managed in operations.
- **Known limitation — deserialization failures:** for a record that failed in `ErrorHandlingDeserializer`, the recoverer publishes the original raw `byte[]`. The application `KafkaTemplate` uses `JsonSerializer` for values, which serializes a `byte[]` as a base64 JSON string, so the DLT copy of a malformed record is not byte-identical to the original. A `ByteArraySerializer` (or `DelegatingByTypeSerializer`) template for the recoverer would be required to preserve raw bytes.

## Alternatives Considered

- **Logging and discarding (Phase 1 baseline):** Rejected. Causes unrecoverable telemetry loss and blinds operational teams to faulty sensors or breaking changes.
- **Retrying indefinitely until manual fix:** Rejected. Completely blocks the partition (Head-of-Line blocking), accumulating massive Kafka lag for all machines hashed to that partition.
