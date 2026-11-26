-- Commerce V2 Phase 3：收藏、商品提醒訂閱、通知偏好與站內通知匣。只新增；V1–V26 不改。
-- 既有 notification_outbox 列與 payload 全部保留；新增 product parent 後仍維持「恰好一個 parent」。

CREATE TABLE customer_favorite (
 customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 product_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(customer_id,product_id),
 INDEX ix_favorite_recent(customer_id,created_at),
 INDEX ix_favorite_product(product_id),
 FOREIGN KEY(customer_id) REFERENCES customer(id),
 FOREIGN KEY(product_id) REFERENCES catalog_product(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 顧客明確訂閱的商品提醒；每種提醒對同一商品最多一筆。
CREATE TABLE customer_product_alert (
 customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 product_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 kind VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(customer_id,product_id,kind),
 INDEX ix_alert_product(product_id,kind),
 FOREIGN KEY(customer_id) REFERENCES customer(id),
 FOREIGN KEY(product_id) REFERENCES catalog_product(id),
 CONSTRAINT ck_alert_kind CHECK(kind IN ('RESTOCK','PREORDER_OPEN','PREORDER_CLOSING','RELEASE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 沒有列＝預設值（Email 關閉、訂單與商品提醒開啟）。偏好只影響 Email 管道；站內通知匣一律保留。
CREATE TABLE customer_notification_preference (
 customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 email_enabled BOOLEAN NOT NULL DEFAULT FALSE,
 order_updates BOOLEAN NOT NULL DEFAULT TRUE,
 product_alerts BOOLEAN NOT NULL DEFAULT TRUE,
 version INT NOT NULL DEFAULT 0,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(customer_id) REFERENCES customer(id),
 CONSTRAINT ck_notification_pref_version CHECK(version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 掃描器的上次觀察狀態：只在狀態轉換時產生提醒；第一次觀察只建立基準，不補發歷史。
CREATE TABLE product_alert_state (
 product_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 in_stock BOOLEAN NOT NULL,
 restock_seq INT NOT NULL DEFAULT 0,
 open_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 closing_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 released_key VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NULL,
 scanned_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(product_id) REFERENCES catalog_product(id),
 CONSTRAINT ck_alert_state_seq CHECK(restock_seq>=0),
 INDEX ix_alert_state_scanned(scanned_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE notification_outbox
 ADD product_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER preorder_order_id,
 ADD read_at DATETIME(6) NULL,
 ADD FOREIGN KEY(product_id) REFERENCES catalog_product(id),
 DROP CHECK ck_notification_parent,
 ADD CONSTRAINT ck_notification_parent_v27 CHECK((order_id IS NOT NULL)+(preorder_order_id IS NOT NULL)+(product_id IS NOT NULL)=1),
 ADD INDEX ix_notification_recipient(recipient_ref,created_at,id),
 ADD INDEX ix_notification_product(product_id,created_at,id);
