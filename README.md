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
| payment   | 8084 | Event-driven payment behind a PaymentProvider abstraction |

## Phase 4: the saga

`order` and `inventory` never call each other directly. `order` publishes
`OrderCreated` (via its outbox) to the `order-events` topic; `inventory`
consumes it, attempts a reservation, and publishes `InventoryReserved` or
`InventoryFailed` (via its OWN outbox) to `inventory-events`; `order` consumes
that and moves the order to `PAYMENT_PENDING` or `CANCELLED`. Both consumers
use Spring Kafka's `@RetryableTopic` for retry-with-backoff and a
dead-letter topic on genuinely unexpected failures -- a business outcome
like "insufficient stock" is caught and turned into an event, never retried.

## Phase 5: payment and compensation

`order` reacts to `InventoryReserved` by moving to `PAYMENT_PENDING` and
publishing `PaymentRequested` (still on `order-events` -- order owns that
topic). `payment` consumes it, charges via a `PaymentProvider` abstraction
(a `FakePaymentProvider` for now; a real gateway is a drop-in replacement),
and publishes `PaymentSucceeded`/`PaymentFailed` to its own `payment-events`
topic, with its own transactional outbox -- the third copy of that pattern.

`PaymentFailed` is the one genuine compensating transaction in this project:
inventory already committed a real reservation to get the order this far, so
order publishes `InventoryReleaseRequested` (back on `order-events`), and
inventory's existing `releaseStock` undoes it. `InventoryFailed`, by
contrast, needs no compensation -- nothing was ever reserved.

Payment never charges twice: a `PaymentAttempt` row keyed by order id is the
idempotency ledger, exactly like inventory's `StockReservation`.

Run any service from its own folder with `mvn spring-boot:run`. If you've
changed `common`, run `mvn install -DskipTests` from the repo root first.

(Architecture diagram, flows and load-test results will be added as we build.)
