-- Commerce V2 Phase 5：出貨單合併與私人倉庫（暫存）。只新增；V1–V27 不改。
-- 合併只搬移 shipment_item 到新的出貨單，不改任何訂單金額、付款或庫存；運費差額只記錄為待審核調整，不自動扣款或退款。

ALTER TABLE shipment
 ADD merged_into CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD held_at DATETIME(6) NULL,
 ADD storage_until DATETIME(6) NULL,
 ADD hold_reminded_at DATETIME(6) NULL,
 ADD FOREIGN KEY(merged_into) REFERENCES shipment(id),
 ADD CONSTRAINT ck_shipment_hold CHECK((held_at IS NULL)=(storage_until IS NULL) AND (held_at IS NULL OR storage_until>held_at)),
 ADD CONSTRAINT ck_shipment_merged CHECK(merged_into IS NULL OR (held_at IS NULL AND tracking_number IS NULL AND merged_into<>id)),
 ADD INDEX ix_shipment_held(held_at,storage_until,id),
 ADD INDEX ix_shipment_merged(merged_into);

-- 顧客端命令（合併、暫存、取消暫存）的冪等紀錄：同 requestId 同內容重送回原結果，內容不同 409。
CREATE TABLE shipment_customer_event (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 action VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 shipment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 detail JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(customer_id) REFERENCES customer(id), FOREIGN KEY(shipment_id) REFERENCES shipment(id),
 CONSTRAINT ck_shipment_customer_action CHECK(action IN ('MERGE','HOLD','RELEASE')),
 INDEX ix_shipment_customer_event(customer_id,created_at,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 合併後運費變少時的差額：只記錄、待店長審核；CREDITED_MOCK 只是狀態，不移動任何金錢。
CREATE TABLE shipping_adjustment (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 shipment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 before_fee_twd INT NOT NULL, after_fee_twd INT NOT NULL, credit_twd INT NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING_REVIEW',
 version INT NOT NULL DEFAULT 1,
 decided_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(shipment_id) REFERENCES shipment(id), FOREIGN KEY(customer_id) REFERENCES customer(id),
 UNIQUE KEY uq_adjustment_shipment(shipment_id),
 CONSTRAINT ck_adjustment CHECK(before_fee_twd>=0 AND after_fee_twd>=0 AND credit_twd=before_fee_twd-after_fee_twd AND credit_twd>0 AND version>=1
   AND status IN ('PENDING_REVIEW','CREDITED_MOCK','DISMISSED') AND ((status='PENDING_REVIEW')=(decided_at IS NULL))),
 INDEX ix_adjustment_status(status,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 員工對倉庫與運費調整的操作稽核（全域 requestId；不可刪改）。
CREATE TABLE shipment_ops_event (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 target_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_id INT NOT NULL, actor_username VARCHAR(64) NOT NULL,
 action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 note VARCHAR(500) NOT NULL, days INT NULL,
 before_version INT NOT NULL, after_version INT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(actor_id) REFERENCES user(id),
 CONSTRAINT ck_shipment_ops CHECK(action IN ('RELEASE_HOLD','EXTEND_HOLD','CREDIT_MOCK','DISMISS') AND after_version=before_version+1 AND (days IS NULL OR days BETWEEN 1 AND 180)),
 INDEX ix_shipment_ops(target_id,created_at,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO permissions_backend(id,name,description,parent_id,level,url,method,admin_router,code) VALUES
 (264,'私人倉庫','暫存中的出貨單與到期狀態',NULL,1,'/api/admin/warehouse','GET','/admin/warehouse','warehouse.read'),
 (265,'倉庫解除暫存','解除顧客暫存（例如逾期）',NULL,1,'/api/admin/warehouse/*/release','POST',NULL,'warehouse.release'),
 (266,'倉庫延長暫存','延長暫存期限',NULL,1,'/api/admin/warehouse/*/extend','POST',NULL,'warehouse.extend'),
 (267,'運費調整','合併出貨產生的運費差額',NULL,1,'/api/admin/shipping-adjustments','GET','/admin/shipping-adjustments','shipping.adjustments.read'),
 (268,'審核運費調整','模擬退回或不處理運費差額',NULL,1,'/api/admin/shipping-adjustments/*/decide','POST',NULL,'shipping.adjustments.decide');
INSERT INTO role_permission VALUES(1,264),(1,265),(1,266),(1,267),(1,268),(2,264),(2,267);
