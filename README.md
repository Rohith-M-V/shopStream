# ShopStream

Event-driven e-commerce backend: Java 21, Spring Boot, Kafka, Redis, MySQL.
Services: gateway, auth, inventory, order, payment, notification.

## Local setup

    docker compose up -d        # MySQL, Kafka (KRaft), Kafka UI, Redis
    docker compose ps           # wait until everything is healthy
    mvn -B verify               # build all modules and run tests

Kafka UI: http://localhost:8090

## Services

| Service   | Port | Responsibility                                    |
|-----------|------|----------------------------------------------------|
| gateway   | 8080 | JWT validation (authentication), routing           |
| auth      | 8081 | Registration, login, RS256 JWT issuing             |
| inventory | 8082 | Product catalog, stock reservation, Redis caching  |
| order     | 8083 | Idempotent order creation, transactional outbox -> Kafka |

## Phase 4: the saga

`order` and `inventory` never call each other directly. `order` publishes
`OrderCreated` (via its outbox) to the `order-events` topic; `inventory`
consumes it, attempts a reservation, and publishes `InventoryReserved` or
`InventoryFailed` (via its OWN outbox) to `inventory-events`; `order` consumes
that and moves the order to `PAYMENT_PENDING` or `CANCELLED`. Both consumers
use Spring Kafka's `@RetryableTopic` for retry-with-backoff and a
dead-letter topic on genuinely unexpected failures -- a business outcome
like "insufficient stock" is caught and turned into an event, never retried.

Run any service from its own folder with `mvn spring-boot:run`. If you've
changed `common`, run `mvn install -DskipTests` from the repo root first.

(Architecture diagram, flows and load-test results will be added as we build.)
