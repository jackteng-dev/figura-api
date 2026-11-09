ALTER TABLE preorder_order DROP CHECK ck_preorder_order_state,
 ADD paid_at DATETIME(6) NULL,
 ADD arrival_status VARCHAR(16) NOT NULL DEFAULT 'NOT_ARRIVED',
 ADD fulfillment_status VARCHAR(16) NOT NULL DEFAULT 'UNFULFILLED',
 ADD CONSTRAINT ck_preorder_order_state CHECK(version>=1 AND ((status='PENDING' AND paid_at IS NULL AND closed_at IS NULL) OR (status IN ('CANCELLED','EXPIRED') AND paid_at IS NULL AND closed_at IS NOT NULL) OR (status='PAID' AND paid_at IS NOT NULL AND closed_at IS NULL) OR (status='REFUNDED' AND paid_at IS NOT NULL AND closed_at IS NOT NULL))),
 ADD CONSTRAINT ck_preorder_order_fulfillment CHECK(arrival_status IN ('NOT_ARRIVED','ARRIVED') AND fulfillment_status IN ('UNFULFILLED','SHIPPED','DELIVERED') AND ((status='PAID') OR (arrival_status='NOT_ARRIVED' AND fulfillment_status='UNFULFILLED')));

CREATE TABLE preorder_mock_payment (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expected_order_version INT NOT NULL,
 transaction_id VARCHAR(41) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 amount_twd BIGINT NOT NULL, currency VARCHAR(3) NOT NULL DEFAULT 'TWD',
 status VARCHAR(16) NOT NULL DEFAULT 'INITIATED',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), settled_at DATETIME(6) NULL,
 UNIQUE KEY uq_preorder_payment_request(customer_id,request_id), UNIQUE KEY uq_preorder_transaction(transaction_id),
 FOREIGN KEY(order_id) REFERENCES preorder_order(id), FOREIGN KEY(customer_id) REFERENCES customer(id),
 CONSTRAINT ck_preorder_payment CHECK(amount_twd>0 AND currency='TWD' AND expected_order_version>=1 AND ((status='INITIATED' AND settled_at IS NULL) OR (status IN ('SUCCEEDED','FAILED','REVIEW_REQUIRED') AND settled_at IS NOT NULL))),
 INDEX ix_preorder_payment_order(order_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE preorder_mock_payment_event (
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 payment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 outcome VARCHAR(8) NOT NULL, disposition VARCHAR(24) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(payment_id) REFERENCES preorder_mock_payment(id),
 CONSTRAINT ck_preorder_payment_event CHECK(outcome IN ('SUCCESS','FAILURE') AND disposition IN ('APPLIED','PAYMENT_FAILED','REVIEW_REQUIRED','ALREADY_SUCCEEDED','ALREADY_FAILED')),
 INDEX ix_preorder_payment_evidence(payment_id,created_at,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE preorder_refund_event (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_id INT NOT NULL, actor_username VARCHAR(64) NOT NULL,
 action VARCHAR(16) NOT NULL, note VARCHAR(500) NOT NULL,
 amount_twd BIGINT NOT NULL, before_version INT NOT NULL, after_version INT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_preorder_refund_payment(payment_id),
 FOREIGN KEY(order_id) REFERENCES preorder_order(id), FOREIGN KEY(payment_id) REFERENCES preorder_mock_payment(id), FOREIGN KEY(actor_id) REFERENCES user(id),
 CONSTRAINT ck_preorder_refund CHECK(action IN ('ORDER_REFUND','REVIEW_REFUND') AND amount_twd>0 AND before_version>=1 AND after_version=before_version+1),
 INDEX ix_preorder_refund_order(order_id,created_at,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Existing V16 ledger stays byte-for-byte intact; payment/refund rows add typed references.
ALTER TABLE preorder_order_event DROP CHECK ck_preorder_event,
 ADD paid_delta INT NOT NULL DEFAULT 0,
 ADD payment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD actor_id INT NULL,
 ADD FOREIGN KEY(payment_id) REFERENCES preorder_mock_payment(id), ADD FOREIGN KEY(actor_id) REFERENCES user(id),
 ADD CONSTRAINT ck_preorder_event CHECK(quantity BETWEEN 1 AND 100 AND held_before>=0 AND held_after>=0 AND held_after=held_before+held_delta AND paid_before>=0 AND paid_after>=0 AND paid_after=paid_before+paid_delta AND order_version>=1 AND batch_version>=1 AND (
 (action='PLACE' AND actor_kind='CUSTOMER' AND held_delta=quantity AND paid_delta=0 AND payment_id IS NULL AND actor_id IS NULL) OR
 (action IN ('CANCEL','EXPIRE') AND actor_kind IN ('CUSTOMER','SYSTEM') AND held_delta=-quantity AND paid_delta=0 AND payment_id IS NULL AND actor_id IS NULL) OR
 (action='PAY' AND actor_kind='SYSTEM' AND held_delta=-quantity AND paid_delta=quantity AND payment_id IS NOT NULL AND actor_id IS NULL) OR
 (action='REFUND' AND actor_kind='STAFF' AND held_delta=0 AND paid_delta=-quantity AND payment_id IS NOT NULL AND actor_id IS NOT NULL)));
INSERT INTO permissions_backend(id,name,description,parent_id,level,url,method,admin_router,code) VALUES
 (246,'預購訂單','獨立預購訂單清單',NULL,1,'/api/admin/preorder-orders','GET','/admin/preorder-orders','preorder-orders.read'),
 (247,'預購訂單詳情','付款證據與退款稽核',NULL,1,'/api/admin/preorder-orders/*','GET',NULL,'preorder-orders.detail'),
 (248,'預購模擬退款','未到貨未出貨全額演練',NULL,1,'/api/admin/preorder-orders/*/mock-refund','POST',NULL,'preorder-orders.refund.mock'),
 (249,'預購付款核對','晚到成功模擬核對退款',NULL,1,'/api/admin/preorder-orders/*/mock-review-refund/*','POST',NULL,'preorder-orders.review.mock');
INSERT INTO role_permission VALUES(1,246),(1,247),(1,248),(1,249),(2,246),(2,247);
