-- V1–V6 are immutable. Payment settlement is a separate atomic inventory event.
ALTER TABLE customer_order DROP CHECK ck_order_state,
 ADD COLUMN paid_at DATETIME(6) NULL,
 ADD CONSTRAINT ck_order_state CHECK(
 (status='PENDING_PAYMENT' AND closed_at IS NULL AND paid_at IS NULL) OR
 (status IN ('CANCELLED','EXPIRED') AND closed_at IS NOT NULL AND paid_at IS NULL) OR
 (status='PAID' AND closed_at IS NULL AND paid_at IS NOT NULL));
ALTER TABLE order_stock_event DROP CHECK ck_stock_event,
 ADD CONSTRAINT ck_stock_event CHECK(action IN ('RESERVE','RELEASE','CONSUME') AND quantity BETWEEN 1 AND 99);
CREATE TABLE mock_payment (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expected_order_version INT NOT NULL,
 transaction_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 amount_twd BIGINT NOT NULL, currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'TWD',
 status VARCHAR(24) NOT NULL DEFAULT 'INITIATED',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), settled_at DATETIME(6) NULL,
 UNIQUE KEY uk_payment_request(customer_id,request_id), INDEX ix_payment_order(order_id,created_at,id),
 CONSTRAINT fk_payment_order FOREIGN KEY(order_id) REFERENCES customer_order(id),
 CONSTRAINT fk_payment_customer FOREIGN KEY(customer_id) REFERENCES customer(id),
 CONSTRAINT ck_payment_amount CHECK(amount_twd>=0 AND currency='TWD' AND expected_order_version>=1),
 CONSTRAINT ck_payment_status CHECK((status='INITIATED' AND settled_at IS NULL) OR
 (status IN ('SUCCEEDED','FAILED','REVIEW_REQUIRED') AND settled_at IS NOT NULL))
) ENGINE=InnoDB;
CREATE TABLE mock_payment_event (
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 payment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 outcome VARCHAR(16) NOT NULL, disposition VARCHAR(32) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX ix_payment_event(payment_id,created_at,event_id),
 CONSTRAINT fk_payment_event FOREIGN KEY(payment_id) REFERENCES mock_payment(id),
 CONSTRAINT ck_payment_event CHECK(outcome IN ('SUCCESS','FAILURE') AND disposition IN
 ('APPLIED','PAYMENT_FAILED','ALREADY_SUCCEEDED','ALREADY_FAILED','REVIEW_REQUIRED'))
) ENGINE=InnoDB;
