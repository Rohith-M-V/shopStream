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
| notification | 8085 | Notifies customers of terminal order outcomes; idempotent consumer |

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

### Real PayPal sandbox integration (optional)

`FakePaymentProvider` is the default and needs no setup -- it's what every
test and a fresh clone use. A real `PayPalPaymentProvider` implements the
same `PaymentProvider` interface and is a drop-in swap via one property;
`PaymentService` itself never changes either way.

To actually exercise it:

1. Create a free account at [developer.paypal.com](https://developer.paypal.com)
   and a Sandbox REST API app, to get a **Client ID** and **Client Secret**.
2. Generate a sandbox test card: Dashboard > Sandbox > Testing Tools >
   Credit Card Generator.
3. Register a webhook (Dashboard > Sandbox > Webhooks) subscribed to
   `PAYMENT.CAPTURE.COMPLETED` and `PAYMENT.CAPTURE.DENIED`, pointing at
   `https://<your-public-url>/api/payments/webhooks/paypal`. PayPal's
   servers need a **public** HTTPS URL to reach this -- for local testing,
   tunnel your gateway with a tool like [ngrok](https://ngrok.com)
   (`ngrok http 8080`) and use the URL it gives you. Copy the **Webhook ID**
   shown after registering.
4. Set these before starting `payment` (PowerShell):

   ```powershell
   $env:PAYMENT_PROVIDER = "paypal"
   $env:PAYPAL_CLIENT_ID = "..."
   $env:PAYPAL_CLIENT_SECRET = "..."
   $env:PAYPAL_WEBHOOK_ID = "..."
   $env:PAYPAL_TEST_CARD_NUMBER = "..."
   $env:PAYPAL_TEST_CARD_EXPIRY = "2027-12"
   $env:PAYPAL_TEST_CARD_CVV = "123"
   cd payment
   mvn spring-boot:run
   ```

Known, named limitation: this provider uses PayPal's `payment_source.card`
(guest/direct card checkout) rather than the usual wallet-approval redirect
flow, since there's no UI in this project to send a buyer through that
redirect. This means it cannot complete a flow requiring extra buyer action
(e.g. a 3D Secure challenge) -- see `PayPalPaymentProvider`'s class comment.
The webhook handler also deliberately does not attempt full reconciliation
purely from webhook data (it only carries the order id and outcome, not
customer/product/quantity) -- see `PayPalWebhookController`'s class comment
for what a production system would add to close that gap.

## Phase 6a: notification

`order` now publishes `OrderConfirmed` and `OrderCancelled` through its
outbox, in the same transaction as the status change. `notification`
consumes them from `order-events` (retry topics + DLT like every other
listener) and records/sends a notification through a `NotificationSender`
interface (default: a logging sender, no infrastructure needed).

It is the project's third idempotency style. Payment dedupes on the business
id (one `PaymentAttempt` per order); order dedupes on current status; the
notification service dedupes on the message itself: an atomic
`INSERT IGNORE` into `processed_events (consumer_group, event_id)` in the
same transaction as the side effect, so a redelivered event is skipped and a
failed attempt rolls its claim back and can be retried.

## Phase 6b: rate limiting (gateway)

The gateway runs its own Redis token-bucket limiter (`gateway/.../ratelimit`),
not Spring Cloud Gateway's built-in one. The whole refill-and-consume step is a
Lua script (`scripts/token_bucket.lua`) executed atomically inside Redis, so
concurrent requests can't both read "one token left" and both get through. It
uses Redis `TIME`, not the gateway's clock, so several gateway instances can't
drift apart.

| Bucket    | Applies to                                  | Key              | Default                      |
|-----------|---------------------------------------------|------------------|------------------------------|
| user      | requests with a valid JWT                   | JWT subject      | burst 50, 25/s               |
| anonymous | public routes without a token               | client IP        | burst 30, 10/s               |
| auth      | `POST /api/auth/login` and `/register`      | client IP        | burst 5, then about 1 per 10s |

Over-limit requests get `429` with `Retry-After`, `X-RateLimit-Remaining` and
`X-RateLimit-Burst-Capacity`. Settings live under `app.rate-limit.*` in the
gateway's `application.yml`.

- **Fails open.** If Redis is down or slower than `redis-timeout-ms`, requests
  pass and `gateway.ratelimit.requests{result=error}` counts it. The cost: login
  brute-force protection is off while Redis is unavailable.
- **Client IP.** By default the socket's remote address. Behind a load balancer
  that is the balancer's address, so set `app.rate-limit.trust-forwarded-for=true`
  (it uses the rightmost `X-Forwarded-For` entry) only when a proxy you control
  sets that header.
- Requests with a missing or invalid token are rejected by Spring Security
  before the limiter runs, so they don't consume tokens.
- Metrics: `gateway.ratelimit.requests` tagged by `policy` and `result`
  (`allowed`, `limited`, `error`), ready for Phase 7.

Run any service from its own folder with `mvn spring-boot:run`. If you've
changed `common`, run `mvn install -DskipTests` from the repo root first.

(Architecture diagram, flows and load-test results will be added as we build.)
