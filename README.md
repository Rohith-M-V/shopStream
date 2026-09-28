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

Run any service from its own folder with `mvn spring-boot:run`. If you've
changed `common`, run `mvn install -DskipTests` from the repo root first.

(Architecture diagram, flows and load-test results will be added as we build.)
