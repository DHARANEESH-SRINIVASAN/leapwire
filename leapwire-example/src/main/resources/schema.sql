CREATE TABLE accounts (
    id BIGINT NOT NULL PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(64) NOT NULL,
    latest_order_id BIGINT NOT NULL
);

CREATE TABLE customers (
    id BIGINT NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    city VARCHAR(64) NOT NULL
);

CREATE TABLE products (
    id BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    price DECIMAL(10, 2) NOT NULL
);

CREATE TABLE orders (
    id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    total DECIMAL(10, 2) NOT NULL,
    views INTEGER DEFAULT 0 NOT NULL
);

CREATE TABLE order_lines (
    id BIGINT NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INTEGER NOT NULL
);
