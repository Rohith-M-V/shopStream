CREATE TABLE notifications (
    id          CHAR(36)      NOT NULL PRIMARY KEY,
    event_id    CHAR(36)      NOT NULL,
    order_id    VARCHAR(64)   NOT NULL,
    customer_id CHAR(36)      NOT NULL,
    type        VARCHAR(30)   NOT NULL,
    subject     VARCHAR(150)  NOT NULL,
    body        VARCHAR(500)  NOT NULL,
    created_at  TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE UNIQUE INDEX uq_notifications_event_id ON notifications (event_id);
CREATE INDEX idx_notifications_order_customer ON notifications (order_id, customer_id);

-- The generic idempotent-consumer ledger. The composite primary key is what
-- makes "claim this event" a single atomic INSERT IGNORE.
CREATE TABLE processed_events (
    consumer_group VARCHAR(100)  NOT NULL,
    event_id       CHAR(36)      NOT NULL,
    processed_at   TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (consumer_group, event_id)
);
