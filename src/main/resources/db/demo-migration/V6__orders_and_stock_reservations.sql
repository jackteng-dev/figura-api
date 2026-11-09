-- Existing quantity remains physical stock; reserved is unavailable for new purchases.
ALTER TABLE sku_inventory ADD COLUMN reserved INT NOT NULL DEFAULT 0,
 ADD CONSTRAINT ck_inventory_reserved CHECK(reserved BETWEEN 0 AND quantity);
CREATE TABLE customer_order (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 cart_version INT NOT NULL, address_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 address_version INT NOT NULL, subtotal_twd BIGINT NOT NULL, shipping_twd INT NOT NULL DEFAULT 0,
 recipient_name VARCHAR(80) NOT NULL, phone VARCHAR(16) NOT NULL, postal_code VARCHAR(6) NOT NULL,
 city VARCHAR(40) NOT NULL, district VARCHAR(40) NOT NULL, street VARCHAR(200) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING_PAYMENT', version INT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), expires_at DATETIME(6) NOT NULL,
 closed_at DATETIME(6) NULL,
 UNIQUE KEY uk_order_request(customer_id,request_id), INDEX ix_order_customer(customer_id,created_at,id),
 INDEX ix_order_expiry(status,expires_at),
 CONSTRAINT fk_order_customer FOREIGN KEY(customer_id) REFERENCES customer(id),
 CONSTRAINT ck_order_totals CHECK(subtotal_twd>=0 AND shipping_twd=0),
 CONSTRAINT ck_order_versions CHECK(cart_version>=0 AND address_version>=1 AND version>=1),
 CONSTRAINT ck_order_state CHECK((status='PENDING_PAYMENT' AND closed_at IS NULL) OR
 (status IN ('CANCELLED','EXPIRED') AND closed_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE customer_order_line (
 order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sku_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 product_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 title VARCHAR(120) NOT NULL, label VARCHAR(80) NOT NULL, quantity INT NOT NULL, unit_price_twd INT NOT NULL,
 PRIMARY KEY(order_id,sku_id),
 CONSTRAINT fk_order_line_order FOREIGN KEY(order_id) REFERENCES customer_order(id),
 CONSTRAINT fk_order_line_sku FOREIGN KEY(sku_id) REFERENCES catalog_sku(id),
 CONSTRAINT ck_order_line CHECK(quantity BETWEEN 1 AND 99 AND unit_price_twd BETWEEN 0 AND 100000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE order_stock_event (
 order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sku_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 action VARCHAR(16) NOT NULL, quantity INT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(order_id,sku_id,action),
 CONSTRAINT fk_stock_event_line FOREIGN KEY(order_id,sku_id) REFERENCES customer_order_line(order_id,sku_id),
 CONSTRAINT ck_stock_event CHECK(action IN ('RESERVE','RELEASE') AND quantity BETWEEN 1 AND 99)
) ENGINE=InnoDB;
