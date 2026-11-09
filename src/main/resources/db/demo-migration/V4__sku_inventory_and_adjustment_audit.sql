-- Preserve V1/V2/V3 checksums. Existing SKUs start at zero; no invented stock.
CREATE TABLE sku_inventory (
 sku_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 quantity INT NOT NULL DEFAULT 0, version INT NOT NULL DEFAULT 0,
 CONSTRAINT fk_inventory_sku FOREIGN KEY(sku_id) REFERENCES catalog_sku(id) ON DELETE CASCADE,
 CONSTRAINT ck_inventory_quantity CHECK(quantity BETWEEN 0 AND 1000000),
 CONSTRAINT ck_inventory_version CHECK(version >= 0)
) ENGINE=InnoDB;
INSERT INTO sku_inventory(sku_id) SELECT id FROM catalog_sku;

CREATE TABLE inventory_adjustment (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 sku_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_id INT NOT NULL, actor_username VARCHAR(64) NOT NULL,
 delta INT NOT NULL, reason VARCHAR(16) NOT NULL, note VARCHAR(500) NOT NULL,
 before_quantity INT NOT NULL, after_quantity INT NOT NULL,
 before_version INT NOT NULL, after_version INT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_adjustment_sku FOREIGN KEY(sku_id) REFERENCES catalog_sku(id),
 CONSTRAINT fk_adjustment_actor FOREIGN KEY(actor_id) REFERENCES user(id),
 CONSTRAINT ck_adjustment_delta CHECK(delta BETWEEN -1000000 AND 1000000 AND delta <> 0),
 CONSTRAINT ck_adjustment_reason CHECK((reason='RESTOCK' AND delta>0) OR (reason='DAMAGE' AND delta<0) OR reason='CORRECTION'),
 CONSTRAINT ck_adjustment_quantities CHECK(before_quantity BETWEEN 0 AND 1000000 AND after_quantity BETWEEN 0 AND 1000000 AND after_quantity=before_quantity+delta),
 CONSTRAINT ck_adjustment_versions CHECK(before_version >= 0 AND after_version=before_version+1),
 INDEX ix_adjustment_sku_created(sku_id,created_at,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO permissions_backend(id,name,description,parent_id,level,url,method,admin_router,code) VALUES
 (20,'查看庫存','SKU目前數量與版本',NULL,1,'/api/admin/products/*/skus/*/inventory','GET',NULL,'inventory.read'),
 (21,'庫存異動紀錄','SKU庫存調整稽核',NULL,1,'/api/admin/products/*/skus/*/inventory/adjustments','GET',NULL,'inventory.audit'),
 (22,'調整庫存','入庫、盤點與損耗',NULL,1,'/api/admin/products/*/skus/*/inventory/adjustments','POST',NULL,'inventory.adjust');
INSERT INTO role_permission VALUES (1,20),(1,21),(1,22),(2,20),(2,21);
