-- V1–V21 are immutable. Phase 4: per-batch payment terms (full or deposit + balance), staged payments,
-- balance call, arrival schedule changes with customer-facing delay history. Additive only: existing
-- batches/orders/payments become FULL through column defaults and keep every original row.

ALTER TABLE preorder_batch
 ADD payment_mode VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'FULL',
 ADD deposit_twd INT NULL,
 ADD balance_days INT NULL,
 ADD balance_called_at DATETIME(6) NULL,
 ADD CONSTRAINT ck_preorder_terms CHECK(
  (payment_mode='FULL' AND deposit_twd IS NULL AND balance_days IS NULL AND balance_called_at IS NULL) OR
  (payment_mode='DEPOSIT' AND deposit_twd BETWEEN 1 AND price_twd-1 AND balance_days BETWEEN 1 AND 60));

-- Order snapshot of the terms. status keeps its V17 meaning for quota: PAID = first payment settled
-- (full price, or the deposit); balance_status tracks the second payment of DEPOSIT orders only.
ALTER TABLE preorder_order
 ADD payment_mode VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'FULL',
 ADD deposit_twd BIGINT NOT NULL DEFAULT 0,
 ADD balance_twd BIGINT NOT NULL DEFAULT 0,
 ADD balance_status VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NONE',
 ADD balance_due_at DATETIME(6) NULL,
 ADD balance_paid_at DATETIME(6) NULL,
 ADD CONSTRAINT ck_preorder_order_terms CHECK(
  (payment_mode='FULL' AND deposit_twd=0 AND balance_twd=0 AND balance_status='NONE' AND balance_due_at IS NULL AND balance_paid_at IS NULL) OR
  (payment_mode='DEPOSIT' AND deposit_twd>0 AND balance_twd>0 AND deposit_twd+balance_twd=subtotal_twd+shipping_twd AND (
   (balance_status='NOT_DUE' AND balance_due_at IS NULL AND balance_paid_at IS NULL) OR
   (balance_status='DUE' AND balance_due_at IS NOT NULL AND balance_paid_at IS NULL AND status IN ('PAID','REFUNDED')) OR
   (balance_status='PAID' AND balance_due_at IS NOT NULL AND balance_paid_at IS NOT NULL AND status IN ('PAID','REFUNDED'))))),
 ADD INDEX ix_preorder_order_balance(batch_id,status,balance_status);

-- Which part of the price a payment attempt covers. Existing rows are FULL.
ALTER TABLE preorder_mock_payment
 ADD stage VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'FULL',
 ADD CONSTRAINT ck_preorder_payment_stage CHECK(stage IN ('FULL','DEPOSIT','BALANCE')),
 ADD INDEX ix_preorder_payment_stage(order_id,stage,status);

-- Staff schedule commands on a batch: balance call, arrival delay or adjustment. Immutable audit;
-- public_reason is the only text shown to customers (staff note and actor never are).
CREATE TABLE preorder_schedule_event (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_id INT NOT NULL, actor_username VARCHAR(64) NOT NULL,
 action VARCHAR(12) NOT NULL, fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 public_reason VARCHAR(200) NOT NULL, note VARCHAR(500) NOT NULL,
 arrival_from_before DATE NOT NULL, arrival_to_before DATE NOT NULL,
 arrival_from_after DATE NOT NULL, arrival_to_after DATE NOT NULL,
 balance_due_at DATETIME(6) NULL, affected_orders INT NOT NULL,
 batch_version_before INT NOT NULL, batch_version_after INT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(batch_id) REFERENCES preorder_batch(id), FOREIGN KEY(actor_id) REFERENCES user(id),
 CONSTRAINT ck_preorder_schedule CHECK(affected_orders>=0 AND batch_version_before>=1 AND batch_version_after=batch_version_before+1 AND arrival_from_after<=arrival_to_after AND (
  (action='BALANCE_CALL' AND balance_due_at IS NOT NULL AND arrival_from_after=arrival_from_before AND arrival_to_after=arrival_to_before) OR
  (action IN ('DELAY','ADJUST') AND balance_due_at IS NULL AND (arrival_from_after<>arrival_from_before OR arrival_to_after<>arrival_to_before)))),
 INDEX ix_preorder_schedule_batch(batch_id,created_at,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO permissions_backend(id,name,description,parent_id,level,url,method,admin_router,code) VALUES
 (256,'預購到貨時程','延期或調整預估到貨並通知顧客',NULL,1,'/api/admin/preorders/*/schedule','POST',NULL,'preorders.schedule'),
 (257,'預購尾款通知','到貨後通知訂金訂單支付尾款',NULL,1,'/api/admin/preorders/*/balance-call','POST',NULL,'preorders.balance.call');
INSERT INTO role_permission VALUES(1,256),(1,257);
