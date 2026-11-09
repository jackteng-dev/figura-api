-- V1–V9 immutable. Preserve historical shipping after a returned-order refund.
ALTER TABLE customer_order ADD COLUMN return_refunded BOOLEAN NOT NULL DEFAULT FALSE, DROP CHECK ck_order_state, DROP CHECK ck_order_fulfillment,
 ADD CONSTRAINT ck_order_state CHECK(
 (status='PENDING_PAYMENT' AND return_refunded=FALSE AND closed_at IS NULL AND paid_at IS NULL) OR
 (status IN ('CANCELLED','EXPIRED') AND return_refunded=FALSE AND closed_at IS NOT NULL AND paid_at IS NULL) OR
 (status='PAID' AND return_refunded=FALSE AND closed_at IS NULL AND paid_at IS NOT NULL) OR
 (status='REFUNDED' AND closed_at IS NOT NULL AND paid_at IS NOT NULL AND ((return_refunded=FALSE AND fulfillment_status='UNFULFILLED') OR (return_refunded=TRUE AND fulfillment_status IN ('SHIPPED','DELIVERED'))))),
 ADD CONSTRAINT ck_order_fulfillment CHECK(
 (fulfillment_status='UNFULFILLED' AND shipment_tracking_id IS NULL AND shipped_at IS NULL AND delivered_at IS NULL) OR
 (status IN ('PAID','REFUNDED') AND shipment_tracking_id IS NOT NULL AND shipment_tracking_id LIKE 'MOCK-%' AND shipped_at IS NOT NULL AND
 ((fulfillment_status='SHIPPED' AND delivered_at IS NULL) OR (fulfillment_status='DELIVERED' AND delivered_at IS NOT NULL AND delivered_at>=shipped_at))));
ALTER TABLE mock_refund_event DROP CHECK ck_refund,
 ADD CONSTRAINT ck_refund CHECK(action IN ('ORDER_REFUND','REVIEW_REFUND','RETURN_REFUND') AND amount_twd>=0 AND before_version>=1 AND after_version=before_version+1);
CREATE TABLE mock_order_return (
 order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expected_order_version INT NOT NULL, reason VARCHAR(500) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'REQUESTED', disposition VARCHAR(16) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 received_at DATETIME(6) NULL, refunded_at DATETIME(6) NULL, rejected_at DATETIME(6) NULL,
 CONSTRAINT fk_return_order FOREIGN KEY(order_id) REFERENCES customer_order(id),
 CONSTRAINT fk_return_customer FOREIGN KEY(customer_id) REFERENCES customer(id),
 CONSTRAINT ck_return CHECK(expected_order_version>=1 AND (
 (status='REQUESTED' AND disposition IS NULL AND received_at IS NULL AND refunded_at IS NULL AND rejected_at IS NULL) OR
 (status='REJECTED' AND disposition IS NULL AND received_at IS NULL AND refunded_at IS NULL AND rejected_at IS NOT NULL AND rejected_at>=created_at) OR
 (status='RECEIVED' AND disposition IS NOT NULL AND disposition IN ('RESTOCK','NO_RESTOCK') AND received_at IS NOT NULL AND received_at>=created_at AND refunded_at IS NULL AND rejected_at IS NULL) OR
 (status='REFUNDED' AND disposition IS NOT NULL AND disposition IN ('RESTOCK','NO_RESTOCK') AND received_at IS NOT NULL AND received_at>=created_at AND refunded_at IS NOT NULL AND refunded_at>=received_at AND rejected_at IS NULL)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE order_return_event (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_id INT NOT NULL, actor_username VARCHAR(64) NOT NULL,
 action VARCHAR(16) NOT NULL, note VARCHAR(500) NOT NULL,
 before_version INT NOT NULL, after_version INT NOT NULL,
 before_status VARCHAR(16) NOT NULL, after_status VARCHAR(16) NOT NULL,
 disposition VARCHAR(16) NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_return_action(order_id,action),
 CONSTRAINT fk_return_event_order FOREIGN KEY(order_id) REFERENCES mock_order_return(order_id),
 CONSTRAINT fk_return_event_actor FOREIGN KEY(actor_id) REFERENCES user(id),
 CONSTRAINT ck_return_event CHECK(before_version>=1 AND after_version=before_version+1 AND (
 (action='RECEIVE' AND before_status='REQUESTED' AND after_status='RECEIVED' AND disposition IS NOT NULL AND disposition IN ('RESTOCK','NO_RESTOCK')) OR
 (action='REJECT' AND before_status='REQUESTED' AND after_status='REJECTED' AND disposition IS NULL) OR
 (action='REFUND' AND before_status='RECEIVED' AND after_status='REFUNDED' AND disposition IS NOT NULL AND disposition IN ('RESTOCK','NO_RESTOCK'))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO permissions_backend(id,name,description,parent_id,level,url,method,admin_router,code) VALUES
 (50,'退貨紀錄','唯讀退貨申請與驗收稽核',NULL,1,'/api/admin/orders/*/returns','GET',NULL,'orders.returns.read'),
 (51,'模擬退貨驗收','整筆退貨收貨及庫存處置',NULL,1,'/api/admin/orders/*/mock-return-receipt','POST',NULL,'orders.return.receive.mock'),
 (52,'拒絕退貨申請','記錄拒絕演練申請',NULL,1,'/api/admin/orders/*/mock-return-rejection','POST',NULL,'orders.return.reject.mock'),
 (53,'模擬退貨退款','已驗收退貨全額退款',NULL,1,'/api/admin/orders/*/mock-return-refund','POST',NULL,'orders.return.refund.mock');
INSERT INTO role_permission VALUES(1,50),(1,51),(1,52),(1,53),(2,50);
