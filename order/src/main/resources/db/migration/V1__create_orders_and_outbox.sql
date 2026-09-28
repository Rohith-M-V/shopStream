-- id is caller-supplied (the Idempotency-Key) -- not AUTO_INCREMENT.
CREATE TABLE orders (
    id          VARCHAR(64) NOT NULL PRIMARY KEY,
    customer_id CHAR(36)    NOT NULL,
    product_id  CHAR(36)    NOT NULL,
    quantity    INT         NOT NULL,
    status      VARCHAR(20) NOT NULL,
    created_at  TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE INDEX idx_orders_customer_id ON orders (customer_id);

CREATE TABLE outbox_events (
    id             CHAR(36)    NOT NULL PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id   VARCHAR(64) NOT NULL,
    event_type     VARCHAR(50) NOT NULL,
    payload        TEXT        NOT NULL,
    created_at     TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at   TIMESTAMP   NULL
);

-- The relay's own query filters on this column on every poll.
CREATE INDEX idx_outbox_unpublished ON outbox_events (published_at, created_at);
