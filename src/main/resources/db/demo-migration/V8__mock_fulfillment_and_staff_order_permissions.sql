-- Additive only: V1–V7 remain immutable. Fulfillment never consumes stock again.
ALTER TABLE customer_order
 ADD COLUMN fulfillment_status VARCHAR(16) NOT NULL DEFAULT 'UNFULFILLED',
 ADD COLUMN shipment_tracking_id VARCHAR(41) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,
 ADD COLUMN shipped_at DATETIME(6) NULL,
 ADD COLUMN delivered_at DATETIME(6) NULL,
 ADD CONSTRAINT ck_order_fulfillment CHECK(
 (fulfillment_status='UNFULFILLED' AND shipment_tracking_id IS NULL AND shipped_at IS NULL AND delivered_at IS NULL) OR
 (status='PAID' AND shipment_tracking_id IS NOT NULL AND shipment_tracking_id LIKE 'MOCK-%' AND shipped_at IS NOT NULL AND
 ((fulfillment_status='SHIPPED' AND delivered_at IS NULL) OR (fulfillment_status='DELIVERED' AND delivered_at IS NOT NULL AND delivered_at>=shipped_at))));
CREATE TABLE order_fulfillment_event (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_id INT NOT NULL, actor_username VARCHAR(64) NOT NULL,
 action VARCHAR(16) NOT NULL, note VARCHAR(500) NOT NULL,
 before_version INT NOT NULL, after_version INT NOT NULL,
 before_status VARCHAR(16) NOT NULL, after_status VARCHAR(16) NOT NULL,
 tracking_id VARCHAR(41) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_fulfillment_action(order_id,action),
 CONSTRAINT fk_fulfillment_order FOREIGN KEY(order_id) REFERENCES customer_order(id),
 CONSTRAINT fk_fulfillment_actor FOREIGN KEY(actor_id) REFERENCES user(id),
 CONSTRAINT ck_fulfillment_versions CHECK(before_version>=1 AND after_version=before_version+1),
 CONSTRAINT ck_fulfillment_action CHECK((action='SHIP' AND before_status='UNFULFILLED' AND after_status='SHIPPED') OR
 (action='DELIVER' AND before_status='SHIPPED' AND after_status='DELIVERED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO permissions_backend(id,name,description,parent_id,level,url,method,admin_router,code) VALUES
 (30,'訂單管理','訂單摘要列表',NULL,1,'/api/admin/orders','GET','/admin/orders','orders.read.list'),
 (31,'訂單詳情','收件及訂單快照',NULL,1,'/api/admin/orders/*','GET',NULL,'orders.read.detail'),
 (32,'模擬出貨','已模擬付款訂單出貨',NULL,1,'/api/admin/orders/*/mock-shipment','POST',NULL,'orders.ship.mock'),
 (33,'模擬送達','記錄模擬送達',NULL,1,'/api/admin/orders/*/mock-delivery','POST',NULL,'orders.deliver.mock'),
 (34,'履約紀錄','唯讀出貨與送達稽核',NULL,1,'/api/admin/orders/*/fulfillment-events','GET',NULL,'orders.audit');
INSERT INTO role_permission VALUES(1,30),(1,31),(1,32),(1,33),(1,34),(2,30),(2,31),(2,34);
