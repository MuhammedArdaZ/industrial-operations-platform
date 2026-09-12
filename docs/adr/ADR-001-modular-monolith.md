# ADR-001: Start as a Modular Monolith

## Context

Industrial Operations Platform must be realistically finishable in two to three months while demonstrating maintainable backend and distributed-systems engineering. The system needs cohesive machine, sensor, telemetry, latest-state, and later alert/maintenance boundaries, but Phase 1 does not require independent deployment or scaling of those concerns.

## Decision

Build one deployable Spring Boot application organized as a modular monolith. Keep domain and adapter boundaries explicit so a component can be extracted later only when a concrete scaling, ownership, reliability-isolation, or deployment-cadence need justifies the operational cost.

## Consequences

- Local development, debugging, testing, and deployment remain simpler.
- Kafka still provides an explicit asynchronous boundary for telemetry without requiring separate services.
- Module boundaries must be maintained deliberately to avoid a tangled monolith.
- Independent deployment and scaling are deferred until they provide demonstrable value.

## Alternatives considered

- **Microservices from the start:** rejected because they add deployment, observability, networking, and coordination complexity before the project has a demonstrated need.
- **Single unstructured application:** rejected because it would make future extraction and maintenance harder.
