-- Preorder quotas are independent of sku_inventory. No preorder order/hold is created in this milestone.
CREATE TABLE preorder_batch (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 product_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, sku_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 name VARCHAR(120) NOT NULL, description VARCHAR(2000) NOT NULL,
 product_title VARCHAR(120) NOT NULL, sku_label VARCHAR(80) NOT NULL,
 price_twd INT NOT NULL, capacity INT NOT NULL,
 held_quantity INT NOT NULL DEFAULT 0, paid_quantity INT NOT NULL DEFAULT 0,
 deadline_at DATETIME(6) NOT NULL, arrival_from DATE NOT NULL, arrival_to DATE NOT NULL,
 status VARCHAR(12) NOT NULL DEFAULT 'DRAFT', version INT NOT NULL DEFAULT 0,
 opened_at DATETIME(6) NULL, closed_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(product_id) REFERENCES catalog_product(id), FOREIGN KEY(sku_id) REFERENCES catalog_sku(id),
 CONSTRAINT ck_preorder_values CHECK(price_twd BETWEEN 1 AND 99999999 AND capacity BETWEEN 1 AND 100000 AND version>=0),
 CONSTRAINT ck_preorder_quota CHECK(held_quantity>=0 AND paid_quantity>=0 AND held_quantity+paid_quantity<=capacity),
 CONSTRAINT ck_preorder_dates CHECK(arrival_from<=arrival_to AND DATE(deadline_at)<=arrival_from),
 CONSTRAINT ck_preorder_status CHECK((status='DRAFT' AND opened_at IS NULL AND closed_at IS NULL) OR (status='OPEN' AND opened_at IS NOT NULL AND closed_at IS NULL) OR (status='CLOSED' AND closed_at IS NOT NULL)),
 INDEX ix_preorder_public(status,deadline_at,id), INDEX ix_preorder_product(product_id,sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE preorder_batch_event (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_id INT NOT NULL, actor_username VARCHAR(64) NOT NULL,
 action VARCHAR(12) NOT NULL, fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 before_state JSON NULL, after_state JSON NOT NULL, note VARCHAR(500) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(batch_id) REFERENCES preorder_batch(id), FOREIGN KEY(actor_id) REFERENCES user(id),
 CONSTRAINT ck_preorder_event_action CHECK(action IN ('CREATE','UPDATE','OPEN','CLOSE')),
 INDEX ix_preorder_audit(batch_id,created_at,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO permissions_backend(id,name,description,parent_id,level,url,method,admin_router,code) VALUES
 (240,'預購批次','批次清單',NULL,1,'/api/admin/preorders','GET','/admin/preorders','preorders.read'),
 (241,'預購批次詳情','批次詳情與唯讀稽核',NULL,1,'/api/admin/preorders/*','GET',NULL,'preorders.detail'),
 (242,'建立預購批次','建立獨立配額草稿',NULL,1,'/api/admin/preorders','POST',NULL,'preorders.create'),
 (243,'編輯預購批次','只有草稿可編輯',NULL,1,'/api/admin/preorders/*','PUT',NULL,'preorders.update'),
 (244,'開放預購批次','固定預購條件',NULL,1,'/api/admin/preorders/*/open','POST',NULL,'preorders.open'),
 (245,'預購批次截單','不可重新開放',NULL,1,'/api/admin/preorders/*/close','POST',NULL,'preorders.close');
INSERT INTO role_permission VALUES(1,240),(1,241),(1,242),(1,243),(1,244),(1,245),(2,240),(2,241);
