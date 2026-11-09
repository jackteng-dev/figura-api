CREATE TABLE customer_cart (
    customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_cart_customer FOREIGN KEY (customer_id) REFERENCES customer(id) ON DELETE CASCADE,
    CONSTRAINT chk_cart_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE customer_cart_item (
    customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sku_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quantity INT NOT NULL,
    title_snapshot VARCHAR(120) NOT NULL,
    label_snapshot VARCHAR(80) NOT NULL,
    PRIMARY KEY (customer_id, sku_id),
    CONSTRAINT fk_cart_item_cart FOREIGN KEY (customer_id) REFERENCES customer_cart(customer_id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_item_sku FOREIGN KEY (sku_id) REFERENCES catalog_sku(id),
    CONSTRAINT chk_cart_quantity CHECK (quantity BETWEEN 1 AND 99)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE customer_address (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recipient_name VARCHAR(80) NOT NULL,
    phone VARCHAR(16) NOT NULL,
    postal_code VARCHAR(6) NOT NULL,
    city VARCHAR(40) NOT NULL,
    district VARCHAR(40) NOT NULL,
    street VARCHAR(200) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX idx_address_customer (customer_id, id),
    CONSTRAINT fk_address_customer FOREIGN KEY (customer_id) REFERENCES customer(id) ON DELETE CASCADE,
    CONSTRAINT chk_address_version CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
