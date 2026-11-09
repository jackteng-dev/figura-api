-- V1–V8 immutable. Historical successful payments and signed notifications stay intact.
ALTER TABLE customer_order DROP CHECK ck_order_state,
 ADD CONSTRAINT ck_order_state CHECK(
 (status='PENDING_PAYMENT' AND closed_at IS NULL AND paid_at IS NULL) OR
 (status IN ('CANCELLED','EXPIRED') AND closed_at IS NOT NULL AND paid_at IS NULL) OR
 (status='PAID' AND closed_at IS NULL AND paid_at IS NOT NULL) OR
 (status='REFUNDED' AND closed_at IS NOT NULL AND paid_at IS NOT NULL AND fulfillment_status='UNFULFILLED'));
ALTER TABLE order_stock_event DROP CHECK ck_stock_event,
 ADD CONSTRAINT ck_stock_event CHECK(action IN ('RESERVE','RELEASE','CONSUME','RETURN') AND quantity BETWEEN 1 AND 99);
CREATE TABLE mock_refund_event (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 actor_id INT NOT NULL, actor_username VARCHAR(64) NOT NULL,
 action VARCHAR(24) NOT NULL, note VARCHAR(500) NOT NULL,
 amount_twd BIGINT NOT NULL, before_version INT NOT NULL, after_version INT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX ix_refund_order(order_id,created_at,request_id),
 CONSTRAINT fk_refund_order FOREIGN KEY(order_id) REFERENCES customer_order(id),
 CONSTRAINT fk_refund_payment FOREIGN KEY(payment_id) REFERENCES mock_payment(id),
 CONSTRAINT fk_refund_actor FOREIGN KEY(actor_id) REFERENCES user(id),
 CONSTRAINT ck_refund CHECK(action IN ('ORDER_REFUND','REVIEW_REFUND') AND amount_twd>=0 AND before_version>=1 AND after_version=before_version+1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO permissions_backend(id,name,description,parent_id,level,url,method,admin_router,code) VALUES
 (40,'退款核對紀錄','唯讀模擬退款與待核對付款',NULL,1,'/api/admin/orders/*/refunds','GET',NULL,'orders.refunds.read'),
 (41,'模擬訂單退款','未出貨全額退款與庫存回補',NULL,1,'/api/admin/orders/*/mock-refund','POST',NULL,'orders.refund.mock'),
 (42,'模擬異常付款退款','核對未結算異常付款',NULL,1,'/api/admin/orders/*/mock-review-refund/*','POST',NULL,'orders.review.mock');
INSERT INTO role_permission VALUES(1,40),(1,41),(1,42),(2,40);
