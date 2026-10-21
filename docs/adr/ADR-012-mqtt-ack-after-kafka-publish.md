# ADR-012: Acknowledge MQTT Only After Kafka Publish Confirmation

## Context

ADR-003 defines at-least-once processing on the Kafka consumer side: the Kafka offset is committed only after the database transaction commits. The ingress side (MQTT -> Kafka) had no equivalent guarantee.

The ingestion client uses Eclipse Paho with QoS 1 and a persistent session (`cleanSession=false`). Paho sends `PUBACK` to Mosquitto as soon as `MqttCallback.messageArrived` returns normally; if it throws, Paho drops the connection without acknowledging, and the broker redelivers the message to the persistent session after reconnect.

`KafkaTelemetryProducer.publish` called `kafkaTemplate.send(...)` and returned without awaiting the result. `send()` only appends the record to the producer's in-memory buffer, so `messageArrived` returned, `PUBACK` was sent, and a broker-side failure surfaced later on a different thread. If Kafka was unavailable, the telemetry was acknowledged to the device side and then lost. This was the only remaining loss window on the normal telemetry path (see `docs/failure-scenarios.md`).

Fixing it exposed two further problems:

1. **Paho callback-thread deadlock on reconnect.** Once a Kafka outage makes `messageArrived` throw, the client reconnects. On reconnect, `connectComplete` ran on the Paho callback thread (`MQTT Call`) and called the synchronous `MqttClient.subscribe`, which blocks until `SUBACK` arrives. At the same time, the persistent-session backlog filled Paho's bounded inbound queue (10 messages), so the receiver thread (`MQTT Rec`) blocked trying to enqueue the next message and could not read the `SUBACK`. Only the callback thread drains that queue, so the two threads waited on each other. Keepalive timeout broke the cycle after ~130 s, after which the same thing happened again; ingestion never recovered even after Kafka returned. A thread dump confirmed it: `MQTT Call` in `MqttClient.subscribe -> waitForCompletion`, `MQTT Rec` in `CommsCallback.messageArrived`. This bug already existed, but it was only reachable once `messageArrived` could fail.
2. **Unbounded wait before the publish timeout applies.** `KafkaProducer.send()` blocks the calling thread while it fetches topic metadata, for up to `max.block.ms` (default 60 s). That happens before a future exists, so the publish timeout cannot bound it. With the broker unreachable, each message held the `MQTT Call` thread for 60 s.

## Decision

1. **Wait for the Kafka acknowledgement before returning from `messageArrived`.** `KafkaTelemetryProducer.publish` calls `send(...).get(publishTimeout)`. The producer already uses `acks=all` and idempotence, so a completed future means the record is durably written to the topic. Any failure (`ExecutionException`, `TimeoutException`, `InterruptedException`) is wrapped in `TelemetryPublishException`. The interrupt flag is restored before rethrowing.
2. **Keep transient failures separate from invalid messages.** `TelemetryPublishException` is deliberately not a subclass of `InvalidMqttMessageException`. `MqttTelemetryClient` catches and discards invalid messages (acknowledging them is correct: redelivery cannot fix them), but rethrows everything else so the message is not acknowledged and the broker redelivers it.
3. **Bound both waits with configuration.**
   - `platform.kafka.publish-timeout` (`KAFKA_PUBLISH_TIMEOUT`, default `10s`): how long to wait for the broker acknowledgement once the record is buffered.
   - `spring.kafka.producer.properties.max.block.ms` (`KAFKA_MAX_BLOCK_MS`, default `5000`): how long `send()` may block on metadata or a full buffer.
   - The worst-case stall of the MQTT callback thread is therefore about 15 s per message, well within the MQTT keepalive.
4. **Subscribe asynchronously.** `MqttTelemetryClient` uses `MqttAsyncClient`. The initial `connect()` is still awaited in `start()` (main thread, so it is safe), but `subscribe` in `connectComplete` registers an `IMqttActionListener` and returns at once. The callback thread never waits for a broker response, so it can keep draining the inbound queue. The listener logs the granted QoS on success and the cause on failure.

## Consequences

- An MQTT message is acknowledged only after Kafka has durably accepted it. Ingress is now at-least-once end to end, matching the consumer side (ADR-003).
- Duplicates on the ingress side are expected and handled downstream. A publish that times out may still be delivered later from the producer buffer, and the broker also redelivers the unacknowledged MQTT message. The `(sensor_id, source_message_id)` constraint (ADR-005) collapses both copies: in the mid-run Kafka outage experiment, recovery produced 4 duplicates, all rejected (`telemetry_duplicates_total`).
- A failure in the metadata phase (`max.block.ms`) never enters the producer buffer, so MQTT redelivery is the only copy and no duplicate is produced (observed: 0 duplicates).
- While Kafka is unavailable, ingestion is deliberately throttled: each message holds the callback thread for up to `max.block.ms + publish-timeout`, then the connection drops and Paho reconnects. The backlog stays in Mosquitto's persistent session instead of being acknowledged and lost. Mosquitto runs without persistence in this deployment, so a broker restart during an outage still loses that backlog.
- Throughput is bounded by publish latency because messages are processed one at a time on the Paho callback thread. This is acceptable for the current telemetry rates.
- While Kafka is down, the asynchronous `SUBACK` callback can be starved by the blocked callback thread, and the subscribe `onFailure` path logs an error on each reconnect cycle. This is noise, not a deadlock: it resolves as soon as Kafka returns.
- A typo in the `publish-timeout` YAML key is silently masked by the `10s` default in `@Value`.

## Alternatives considered

- **Keep fire-and-forget publishing:** rejected; it acknowledges telemetry that may never reach Kafka.
- **Await only `max.block.ms` failures (the previous state):** rejected; it covers metadata failures but not a broker that fails after the record is buffered.
- **Paho manual acknowledgements (`setManualAcks(true)` + `messageArrivedComplete`):** would let the callback thread return immediately and acknowledge when the Kafka future completes, allowing several publishes in flight. Deferred: it adds out-of-order acknowledgement and in-flight limit handling that current throughput does not need. It is the scaling path if publish latency becomes the bottleneck.
- **Transactional outbox in PostgreSQL:** would make ingress durable without Kafka being available, but adds a write to the database on the ingress path and a relay process, and goes against ADR-002's use of Kafka as the asynchronous boundary. Rejected for now.
- **MQTT QoS 2:** gives exactly-once between the device and Mosquitto only; it does not cover the Mosquitto -> Kafka handoff, which is where the loss occurred. Downstream idempotency (ADR-005) already handles QoS 1 duplicates.
- **Long `max.block.ms` (default 60 s):** rejected; it stalls the callback thread for a minute per message and delays the reconnect that lets the broker redeliver.
