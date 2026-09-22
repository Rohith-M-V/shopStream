# ShopStream

Event-driven e-commerce backend: Java 21, Spring Boot, Kafka, Redis, MySQL.
Services: gateway, auth, inventory, order, payment, notification.

## Local setup

    docker compose up -d        # MySQL, Kafka (KRaft), Kafka UI, Redis
    docker compose ps           # wait until everything is healthy
    mvn -B verify               # build all modules and run tests

Kafka UI: http://localhost:8090

(Architecture diagram, flows and load-test results will be added as we build.)
