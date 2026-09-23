CREATE TABLE products (
    id             CHAR(36)      NOT NULL PRIMARY KEY,
    sku            VARCHAR(64)   NOT NULL,
    name           VARCHAR(255)  NOT NULL,
    description    VARCHAR(1000),
    price          DECIMAL(10,2) NOT NULL,
    stock_quantity INT           NOT NULL,
    version        BIGINT        NOT NULL DEFAULT 0,
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_products_sku UNIQUE (sku)
);

-- id is caller-supplied (see StockReservation) -- not AUTO_INCREMENT.
CREATE TABLE stock_reservations (
    id          VARCHAR(64)  NOT NULL PRIMARY KEY,
    product_id  CHAR(36)     NOT NULL,
    quantity    INT          NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reservation_product FOREIGN KEY (product_id) REFERENCES products (id)
);
