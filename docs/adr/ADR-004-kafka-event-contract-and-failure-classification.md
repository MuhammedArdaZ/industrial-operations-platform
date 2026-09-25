# ADR-004: Kafka Telemetry Event Contract and Phase 1 Failure Classification

## Context

Phase 1 moves validated telemetry from the ingestion boundary to persistence over a Kafka topic. Before the producer and consumer can work, three things must be decided explicitly rather than inherited from framework defaults:

1. **How the consumer determines the Java type of a message.** Spring Kafka can carry the producing class name in a `__TypeId__` header, or the consumer can fix the type it expects.
2. **When a Kafka offset is committed** relative to the database transaction described in ADR-003.
3. **What happens to a message that cannot be processed.** Spring Kafka's default behaviour retries ten times and then logs and skips, which silently discards data without the project ever having chosen that.

The event envelope already carries `schemaVersion`, and the sensor simulator is expected to be written in Python, so the wire format must not depend on Java class names.

## Decision

**Serialization.** Telemetry events are serialized as JSON. The producer does **not** add type headers (`spring.json.add.type.headers: false`). The consumer ignores type headers (`spring.json.use.type.headers: false`) and fixes its expected type (`spring.json.value.default.type`). Schema evolution is governed by the `schemaVersion` field in the payload, not by the Java type of the message.

**Deserialization failures.** The consumer wraps its value deserializer in `ErrorHandlingDeserializer`. A malformed message therefore reaches the container's error handler instead of being thrown during `poll()`, which would otherwise leave the consumer retrying the same offset indefinitely.

**Partition key.** The producer keys telemetry events by `machineId`. The latest-state projection maintains exactly one row per machine, so keying by machine keeps all observations for one machine in a single partition and therefore in one consumer's ordered stream. `sensorId` was rejected as a key because it does not provide that guarantee once a machine has more than one sensor.

**Offset commit.** `spring.kafka.consumer.enable-auto-commit` is `false` and `spring.kafka.listener.ack-mode` is `record`. The listener method carries the `@Transactional` boundary, so the database transaction commits before the listener returns and the offset is committed after it returns, as ADR-003 requires.

**Failure classification.** Failures are split into two classes:

- **Permanent:** the message can never be processed successfully — an unexpected `eventType`, an unsupported `schemaVersion`, or a missing required field. The consumer validates the envelope before starting any database work and throws `InvalidTelemetryEventException`. That exception is registered as non-retryable on a `DefaultErrorHandler` bean, so the message is logged and skipped without pointless retries. The exception message carries `eventId` and `sourceMessageId` so the skipped message is traceable.
- **Transient:** anything else, such as a database outage. These propagate and keep the default retry behaviour (`FixedBackOff(0L, 9)`, ten attempts), after which Kafka redelivery remains possible.

**Reference validation is not repeated in the consumer.** The ingestion boundary already validated the sensor against platform-owned configuration and resolved the machine from it. The Kafka topic is an internal channel, so the consumer does not re-query the sensor configuration for every message. Foreign keys on the `telemetry` table remain the backstop if a referenced sensor or machine is removed later.

## Consequences

- The wire format is language-neutral, so the Python simulator and any future consumer can produce and read the same JSON.
- Java classes can be renamed or moved without invalidating messages already on the topic.
- Permanently invalid messages are still **discarded** in Phase 1; they are logged rather than preserved. A Dead Letter Queue is Phase 2 work, and `DefaultErrorHandler` is the single place where a `DeadLetterPublishingRecoverer` will be attached. Both deserialization failures and validation failures already flow through that one path.
- `ack-mode: record` commits one offset per record, which costs throughput. Phase 1 sets no performance target; revisiting this against `batch` belongs to the Phase 2 backpressure work and should be driven by measurement.
- A single `DefaultErrorHandler` bean applies to every listener container in the application. This is acceptable while telemetry is the only consumer and must be revisited when a second listener is added.
- Adding a measurement, such as pressure, requires a `schemaVersion` increase and an explicit compatibility decision in the consumer; the type system will not signal it.

## Alternatives considered

- **Type headers on the wire:** rejected because it binds the message format to Java package and class names, breaks on refactoring, and cannot be produced by the Python simulator.
- **`spring.json.type.mapping` (logical name to class):** a reasonable growth path once one topic carries several event types, but unnecessary while there is exactly one.
- **Swallowing invalid messages inside the listener:** rejected because it creates a second failure path separate from deserialization failures, forcing Phase 2 to wire a DLQ in two places.
- **Re-validating the sensor and machine in the consumer:** rejected because it adds a database query per message to re-check what the trust boundary already established.
