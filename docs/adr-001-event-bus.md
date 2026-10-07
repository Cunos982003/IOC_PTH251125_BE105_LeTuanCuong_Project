# ADR-001: Event Bus Selection - RabbitMQ over Kafka

**Date:** 2026-10-07
**Status:** Accepted

## Context

The ride-hailing system requires an event-driven architecture for inter-service communication:
- `user-service` publishes `UserRegistered` and consumes `TripCompleted`/`TripCancelled`
- `dispatch-service` publishes `DriverOffered`, `TripCompleted`, `TripCancelled`
- `payment-service` consumes `UserRegistered`, `TripCompleted`, `TripCancelled`
- `ws-gateway` consumes `DriverOffered` for WebSocket push to drivers

Requirements:
- **Reliability**: No message loss (at-least-once delivery)
- **VPS constraints**: 8GB RAM, single-node deployment
- **Operational simplicity**: Minimal moving parts, easy troubleshooting
- **Spring Boot 3.3** native integration
- **Dead Letter Queue** support for failed messages
- **Publisher confirms** for outbox pattern guarantee

## Decision

**Choose RabbitMQ 3.13 (topic exchange) over Kafka + ZooKeeper/KRaft.**

## Rationale

### Why NOT Kafka + ZooKeeper/KRaft

| Factor | Kafka | RabbitMQ |
|--------|-------|----------|
| **RAM (8GB VPS)** | ~2-3GB for broker + 1GB ZooKeeper/KRaft | ~500MB-1GB |
| **Operational complexity** | Two processes (broker + controller) or KRaft metadata quorum | Single process |
| **ZooKeeper** | Deprecated, KRaft still maturing for production | N/A |
| **Spring Boot integration** | Spring Cloud Stream Kafka | Native `spring-boot-starter-amqp` |
| **Message routing** | Partitions + keys (manual) | Topic exchange + routing keys (declarative) |
| **DLQ** | Manual topic + consumer config | Built-in Dead Letter Exchange |
| **Publisher confirms** | Idempotent producer + transactions | Native publisher confirms (correlated) |
| **Message TTL / delayed** | Requires plugins / separate topics | Per-message TTL, delayed exchange plugin |

### Why RabbitMQ

1. **Single binary, low footprint** — fits 8GB VPS comfortably alongside 7 Spring Boot services + PostgreSQL + Redis
2. **Topic exchange** maps naturally to our routing: `users.registered`, `trips.driver_offered`, `trips.completed`, `trips.cancelled`
3. **Dead Letter Exchange (DLX)** — built-in, just declare `events.dlx` + bind DLQs
4. **Publisher confirms (correlated)** — perfect for outbox pattern: publish → wait for confirm → mark outbox `sent=true`
5. **Spring AMQP** — first-class support, `@RabbitListener` with retry/DLQ config via `SimpleRabbitListenerContainerFactory`
6. **Management UI** — port 15672 for queue inspection, message replay, troubleshooting
7. **No ZooKeeper/KRaft** — one less failure domain

## Architecture

```
┌─────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  Publisher  │────▶│  Exchange: events │────▶│   Queues        │
│  (outbox)   │     │  (topic, durable) │     │  user.registered│
└─────────────┘     └──────────────────┘     │  payment.users. │
                                             │  registered     │
       ▲                                     │  ws.offers      │
       │                                     │  payment.trips. │
       │         ┌──────────────────┐        │    completed    │
       └─────────│  DLX: events.dlx  │◀──────│  payment.trips. │
                 │  (topic, durable) │       │    cancelled    │
                 └──────────────────┘       │  user.trips     │
                                             └─────────────────┘
```

- **Exchange**: `events` (topic, durable)
- **DLX**: `events.dlx` (topic, durable) — all queues bind with `x-dead-letter-exchange=events.dlx`
- **DLQs**: `events.dlq` (catch-all) or per-queue DLQs
- **Publisher confirms**: `publisher-confirm-type=correlated`, `mandatory=true`, `publisher-returns=true`
- **Consumer**: `@RabbitListener(autoStartup="false")` + manual start in tests; `acknowledge-mode=manual`; retry 3× with exponential backoff; reject non-requeue → DLQ

## Consequences

### Positive
- Lower resource usage on VPS
- Simpler operations (one service to monitor)
- Native Spring Boot integration
- Declarative routing matches domain events
- Built-in DLQ + management UI

### Negative / Trade-offs
- **At-least-once delivery** → consumers **MUST be idempotent** (use `INSERT ... ON CONFLICT DO NOTHING` on trip_id)
- **No ordering guarantee across partitions** — not needed (each trip is independent)
- **Offer events (DriverOffered) skip outbox** — published directly from dispatch-service for latency; acceptable since offer expiration provides natural deduplication
- **Horizontal scaling** — RabbitMQ clustering more complex than Kafka partitions; not needed for current scale

## Implementation Notes

### Outbox Pattern (user-service, dispatch-service, payment-service for UserRegistered)
```java
// 1. Write to outbox table in same DB transaction
outboxRepository.insert(eventType, routingKey, payload);

// 2. OutboxWorker polls unsent, publishes with confirm
rabbitTemplate.convertAndSend("events", routingKey, payload, msg -> {
    msg.getMessageProperties().setCorrelationId(correlationId);
    return msg;
});
// 3. Wait for ConfirmCallback (correlated) → mark outbox sent=true
```

### Consumer Idempotency
```java
@RabbitListener(queues = "payment.trips.completed")
void handle(Message message) {
    TripEvent event = parse(message);
    // INSERT ... ON CONFLICT (trip_id) DO NOTHING
    settlementService.settle(event.tripId(), ...);
}
```

### Queue Topology
Defined **only in** `infra/rabbitmq/definitions.json` (imported at broker startup).
Services **do not** declare queues/exchanges/bindings.

## References
- `infra/rabbitmq/definitions.json` — topology source of truth
- `docs/contracts/events/*.json` — event payload schemas
- `CLAUDE.md` — architectural rules (section 5)