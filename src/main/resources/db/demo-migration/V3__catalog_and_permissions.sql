-- Additive demo migration; V1/V2 checksums stay unchanged.
ALTER TABLE permissions_backend ADD COLUMN code VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin UNIQUE;
INSERT INTO permissions_backend(id,name,description,parent_id,level,url,method,admin_router,code) VALUES
 (10,'商品管理','商品列表',NULL,1,'/api/admin/products','GET','/admin/products','catalog.read.list'),
 (11,'商品詳情','讀取草稿與 SKU',NULL,1,'/api/admin/products/*','GET',NULL,'catalog.read.detail'),
 (12,'建立商品','建立草稿',NULL,1,'/api/admin/products','POST',NULL,'catalog.write.create'),
 (13,'編輯商品','更新草稿與 SKU',NULL,1,'/api/admin/products/*','PUT',NULL,'catalog.write.update'),
 (14,'封存商品','封存下架商品',NULL,1,'/api/admin/products/*','DELETE',NULL,'catalog.write.archive'),
 (15,'商品上架','上架或下架',NULL,1,'/api/admin/products/*/publication','PATCH',NULL,'catalog.publish');
INSERT INTO role_permission VALUES (1,10),(1,11),(1,12),(1,13),(1,14),(1,15),(2,10),(2,11),(2,12),(2,13),(2,14);
UPDATE role SET description='可編輯商品草稿；不能上架' WHERE id=2;

CREATE TABLE catalog_product (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 title VARCHAR(120) NOT NULL, brand VARCHAR(80) NOT NULL, category VARCHAR(80) NOT NULL,
 description TEXT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', version INT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT ck_product_status CHECK(status IN ('DRAFT','PUBLISHED','ARCHIVED')),
 CONSTRAINT ck_product_version CHECK(version >= 0),
 INDEX ix_catalog_status_created(status,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE catalog_sku (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 product_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 label VARCHAR(80) NOT NULL, price_twd INT NOT NULL, enabled BOOLEAN NOT NULL,
 CONSTRAINT fk_sku_product FOREIGN KEY(product_id) REFERENCES catalog_product(id),
 CONSTRAINT ck_sku_price CHECK(price_twd BETWEEN 1 AND 99999999),
 INDEX ix_sku_product_enabled(product_id,enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
