-- Preorder supply stays separate from sku_inventory and stock orders.
ALTER TABLE preorder_batch
 ADD received_quantity INT NOT NULL DEFAULT 0,
 ADD allocated_quantity INT NOT NULL DEFAULT 0,
 ADD shipped_quantity INT NOT NULL DEFAULT 0,
 ADD CONSTRAINT ck_preorder_supply CHECK(0<=shipped_quantity AND shipped_quantity<=allocated_quantity AND allocated_quantity<=received_quantity AND received_quantity<=capacity);
ALTER TABLE preorder_order
 ADD allocated_quantity INT NOT NULL DEFAULT 0,
 ADD allocated_at DATETIME(6) NULL,
 ADD tracking_number VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD shipped_at DATETIME(6) NULL,
 ADD delivered_at DATETIME(6) NULL,
 ADD CONSTRAINT ck_preorder_allocation CHECK((allocated_quantity=0 AND allocated_at IS NULL AND arrival_status='NOT_ARRIVED') OR (allocated_quantity=quantity AND allocated_at IS NOT NULL AND arrival_status='ARRIVED' AND status='PAID')),
 ADD CONSTRAINT ck_preorder_shipping CHECK((fulfillment_status='UNFULFILLED' AND tracking_number IS NULL AND shipped_at IS NULL AND delivered_at IS NULL) OR (fulfillment_status='SHIPPED' AND allocated_quantity=quantity AND tracking_number IS NOT NULL AND shipped_at IS NOT NULL AND delivered_at IS NULL) OR (fulfillment_status='DELIVERED' AND allocated_quantity=quantity AND tracking_number IS NOT NULL AND shipped_at IS NOT NULL AND delivered_at IS NOT NULL));
CREATE TABLE preorder_fulfillment_event (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 actor_id INT NOT NULL, actor_username VARCHAR(64) NOT NULL,
 action VARCHAR(8) NOT NULL, fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 note VARCHAR(500) NOT NULL, quantity INT NOT NULL,
 received_before INT NOT NULL, received_after INT NOT NULL,
 allocated_before INT NOT NULL, allocated_after INT NOT NULL,
 shipped_before INT NOT NULL, shipped_after INT NOT NULL,
 batch_version_before INT NOT NULL, batch_version_after INT NOT NULL,
 order_version_before INT NULL, order_version_after INT NULL,
 tracking_number VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(batch_id) REFERENCES preorder_batch(id), FOREIGN KEY(order_id) REFERENCES preorder_order(id), FOREIGN KEY(actor_id) REFERENCES user(id),
 UNIQUE KEY uq_preorder_fulfillment_once(order_id,action),
 INDEX ix_preorder_supply_audit(batch_id,created_at,request_id),
 CONSTRAINT ck_preorder_fulfillment_event CHECK(quantity>0 AND batch_version_before>=1 AND batch_version_after=batch_version_before+1 AND 0<=shipped_before AND shipped_before<=allocated_before AND allocated_before<=received_before AND 0<=shipped_after AND shipped_after<=allocated_after AND allocated_after<=received_after AND (
 (action='RECEIVE' AND order_id IS NULL AND order_version_before IS NULL AND order_version_after IS NULL AND tracking_number IS NULL AND received_after=received_before+quantity AND allocated_after=allocated_before AND shipped_after=shipped_before) OR
 (action='ALLOCATE' AND order_id IS NOT NULL AND order_version_before IS NOT NULL AND order_version_after IS NOT NULL AND order_version_before>=1 AND order_version_after=order_version_before+1 AND tracking_number IS NULL AND received_after=received_before AND allocated_after=allocated_before+quantity AND shipped_after=shipped_before) OR
 (action='SHIP' AND order_id IS NOT NULL AND order_version_before IS NOT NULL AND order_version_after IS NOT NULL AND order_version_before>=1 AND order_version_after=order_version_before+1 AND tracking_number IS NOT NULL AND received_after=received_before AND allocated_after=allocated_before AND shipped_after=shipped_before+quantity) OR
 (action='DELIVER' AND order_id IS NOT NULL AND order_version_before IS NOT NULL AND order_version_after IS NOT NULL AND order_version_before>=1 AND order_version_after=order_version_before+1 AND tracking_number IS NOT NULL AND received_after=received_before AND allocated_after=allocated_before AND shipped_after=shipped_before)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO permissions_backend(id,name,description,parent_id,level,url,method,admin_router,code) VALUES
 (250,'預購到貨','預購獨立到貨數量清單',NULL,1,'/api/admin/preorder-arrivals','GET','/admin/preorder-arrivals','preorders.arrivals.read'),
 (251,'預購到貨詳情','到貨分配與履約稽核',NULL,1,'/api/admin/preorder-arrivals/*','GET',NULL,'preorders.arrivals.detail'),
 (252,'預購到貨登記','獨立到貨數量登記',NULL,1,'/api/admin/preorder-arrivals/*/receive','POST',NULL,'preorders.arrivals.receive'),
 (253,'預購整筆分配','將已到貨數量分配給已付款訂單',NULL,1,'/api/admin/preorder-orders/*/allocate','POST',NULL,'preorder-orders.allocate'),
 (254,'預購模擬出貨','已付款已分配且無待核對才出貨',NULL,1,'/api/admin/preorder-orders/*/mock-ship','POST',NULL,'preorder-orders.ship.mock'),
 (255,'預購模擬送達','保留付款與出貨歷史',NULL,1,'/api/admin/preorder-orders/*/mock-deliver','POST',NULL,'preorder-orders.deliver.mock');
INSERT INTO role_permission VALUES(1,250),(1,251),(1,252),(1,253),(1,254),(1,255),(2,250),(2,251);
