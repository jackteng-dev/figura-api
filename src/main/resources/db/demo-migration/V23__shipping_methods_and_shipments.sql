-- V1–V22 are immutable. Phase 5: shipping methods with fee rules, checkout delivery choice,
-- shipments that group one or more orders (combined or split), test logistics adapter only.
-- Additive: existing orders keep shipping_twd=0 and get no shipment row (they keep the
-- original per-order mock fulfillment); only new checkouts create shipments.

-- Allow a shipping fee on the order that carries a shipment (was fixed at 0 by V6/V16).
ALTER TABLE customer_order DROP CHECK ck_order_totals,
 ADD CONSTRAINT ck_order_totals CHECK(subtotal_twd>=0 AND shipping_twd BETWEEN 0 AND 100000);
ALTER TABLE preorder_order DROP CHECK ck_preorder_order_amount,
 ADD CONSTRAINT ck_preorder_order_amount CHECK(unit_price_twd BETWEEN 1 AND 99999999 AND quantity BETWEEN 1 AND 100 AND subtotal_twd=unit_price_twd*quantity AND shipping_twd BETWEEN 0 AND 100000);

-- Fee rules per method. Seeded values are test defaults; the author sets real rates before go-live.
CREATE TABLE shipping_method (
 code VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 name VARCHAR(40) NOT NULL,
 fee_twd INT NOT NULL, free_threshold_twd INT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version INT NOT NULL DEFAULT 1, updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT ck_shipping_method CHECK(code IN ('HOME','CVS') AND fee_twd BETWEEN 0 AND 100000 AND (free_threshold_twd IS NULL OR free_threshold_twd BETWEEN 1 AND 99999999) AND version>=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO shipping_method(code,name,fee_twd,free_threshold_twd) VALUES ('HOME','宅配到府',120,3000),('CVS','超商取貨',60,1500);

CREATE TABLE shipping_method_event (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 code VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_id INT NOT NULL, actor_username VARCHAR(64) NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 before_state JSON NOT NULL, after_state JSON NOT NULL, note VARCHAR(500) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(code) REFERENCES shipping_method(code), FOREIGN KEY(actor_id) REFERENCES user(id),
 INDEX ix_shipping_method_audit(code,created_at,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One shipment = one parcel to one destination. SPLIT checkouts get one shipment per order;
-- COMBINED checkouts put every order of the checkout in one shipment (stock waits for preorders).
-- destination is a snapshot (home address, or a test convenience-store pickup point).
CREATE TABLE shipment (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 group_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 method VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 strategy VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 destination JSON NOT NULL,
 fee_twd INT NOT NULL, goods_twd BIGINT NOT NULL,
 carrier VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'TEST',
 tracking_number VARCHAR(41) CHARACTER SET ascii COLLATE ascii_bin NULL,
 shipped_at DATETIME(6) NULL, delivered_at DATETIME(6) NULL,
 version INT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(customer_id) REFERENCES customer(id), FOREIGN KEY(group_id) REFERENCES checkout_group(id), FOREIGN KEY(method) REFERENCES shipping_method(code),
 UNIQUE KEY uq_shipment_tracking(tracking_number),
 CONSTRAINT ck_shipment CHECK(strategy IN ('SPLIT','COMBINED') AND carrier='TEST' AND fee_twd BETWEEN 0 AND 100000 AND goods_twd>=0 AND version>=1 AND (
  (tracking_number IS NULL AND shipped_at IS NULL AND delivered_at IS NULL) OR
  (tracking_number LIKE 'MOCK-%' AND shipped_at IS NOT NULL AND (delivered_at IS NULL OR delivered_at>=shipped_at)))),
 INDEX ix_shipment_owner(customer_id,created_at,id), INDEX ix_shipment_recent(created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE shipment_item (
 shipment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 position INT NOT NULL,
 stock_order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 preorder_order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 PRIMARY KEY(shipment_id,position),
 FOREIGN KEY(shipment_id) REFERENCES shipment(id), FOREIGN KEY(stock_order_id) REFERENCES customer_order(id), FOREIGN KEY(preorder_order_id) REFERENCES preorder_order(id),
 UNIQUE KEY uq_shipment_stock(stock_order_id), UNIQUE KEY uq_shipment_preorder(preorder_order_id),
 CONSTRAINT ck_shipment_item CHECK(position BETWEEN 0 AND 50 AND ((stock_order_id IS NOT NULL AND preorder_order_id IS NULL) OR (stock_order_id IS NULL AND preorder_order_id IS NOT NULL)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Immutable staff audit for shipment-level ship / deliver through the test adapter.
CREATE TABLE shipment_event (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 shipment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_id INT NOT NULL, actor_username VARCHAR(64) NOT NULL,
 action VARCHAR(8) NOT NULL, note VARCHAR(500) NOT NULL,
 before_version INT NOT NULL, after_version INT NOT NULL,
 tracking_number VARCHAR(41) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(shipment_id) REFERENCES shipment(id), FOREIGN KEY(actor_id) REFERENCES user(id),
 UNIQUE KEY uq_shipment_action(shipment_id,action),
 CONSTRAINT ck_shipment_event CHECK(action IN ('SHIP','DELIVER') AND after_version=before_version+1),
 INDEX ix_shipment_audit(shipment_id,created_at,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The checkout's delivery choice is part of its idempotency fingerprint; keep it on the group for audit.
ALTER TABLE checkout_group
 ADD shipping_method VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD delivery_strategy VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD shipping_twd INT NOT NULL DEFAULT 0;

INSERT INTO permissions_backend(id,name,description,parent_id,level,url,method,admin_router,code) VALUES
 (258,'出貨單','出貨單清單（測試物流）',NULL,1,'/api/admin/shipments','GET','/admin/shipments','shipments.read'),
 (259,'出貨單詳情','出貨單內容與稽核',NULL,1,'/api/admin/shipments/*','GET',NULL,'shipments.detail'),
 (260,'出貨單模擬出貨','整張出貨單一起出貨',NULL,1,'/api/admin/shipments/*/ship','POST',NULL,'shipments.ship.mock'),
 (261,'出貨單模擬送達','整張出貨單一起送達',NULL,1,'/api/admin/shipments/*/deliver','POST',NULL,'shipments.deliver.mock'),
 (262,'配送方式與運費','查看配送方式與運費規則',NULL,1,'/api/admin/shipping-methods','GET','/admin/shipping-methods','shipping.methods.read'),
 (263,'修改運費規則','修改運費與免運門檻',NULL,1,'/api/admin/shipping-methods/*','PUT',NULL,'shipping.methods.update');
INSERT INTO role_permission VALUES(1,258),(1,259),(1,260),(1,261),(1,262),(1,263),(2,258),(2,259),(2,262);
