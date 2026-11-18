-- Phase 3：統一購物車與結帳分組。只新增表，不改既有表與資料（V1–V20 不動）。
-- 現貨購物車（customer_cart_item，以 SKU 為鍵）維持原樣；預購以「批次」為鍵另存，同 SKU 不同批次不會被合併。
-- 整車版本共用 customer_cart.version：任何一種品項變更都遞增，結帳時一起核對。
CREATE TABLE customer_cart_preorder (
    customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quantity INT NOT NULL,
    added_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (customer_id, batch_id),
    INDEX idx_cart_preorder_batch (batch_id),
    CONSTRAINT fk_cart_preorder_cart FOREIGN KEY (customer_id) REFERENCES customer_cart(customer_id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_preorder_batch FOREIGN KEY (batch_id) REFERENCES preorder_batch(id),
    CONSTRAINT chk_cart_preorder_quantity CHECK (quantity BETWEEN 1 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 一次結帳（同一個 requestId）建立的所有子訂單。整組在同一個資料庫交易建立，任一子單失敗全部回滾。
CREATE TABLE checkout_group (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cart_version INT NOT NULL,
    address_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    address_version INT NOT NULL,
    expected_total_twd BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_checkout_group_request (customer_id, request_id),
    CONSTRAINT fk_checkout_group_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT chk_checkout_group_total CHECK (expected_total_twd >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE checkout_group_order (
    group_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    position INT NOT NULL,
    stock_order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    preorder_order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (group_id, position),
    UNIQUE KEY uk_checkout_group_stock (stock_order_id),
    UNIQUE KEY uk_checkout_group_preorder (preorder_order_id),
    CONSTRAINT fk_checkout_group_order_group FOREIGN KEY (group_id) REFERENCES checkout_group(id),
    CONSTRAINT fk_checkout_group_order_stock FOREIGN KEY (stock_order_id) REFERENCES customer_order(id),
    CONSTRAINT fk_checkout_group_order_preorder FOREIGN KEY (preorder_order_id) REFERENCES preorder_order(id),
    CONSTRAINT chk_checkout_group_order_one CHECK ((stock_order_id IS NULL) <> (preorder_order_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
