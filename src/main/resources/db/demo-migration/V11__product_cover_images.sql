-- V1–V10 immutable; private immutable image assets, current cover and durable garbage collection.
CREATE TABLE product_image_asset (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 product_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 object_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 content_type VARCHAR(32) NOT NULL, alt VARCHAR(160) NOT NULL,
 width INT NOT NULL,height INT NOT NULL,size_bytes INT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(product_id) REFERENCES catalog_product(id),
 CONSTRAINT ck_image_asset CHECK(content_type IN ('image/png','image/jpeg') AND width BETWEEN 1 AND 4096 AND height BETWEEN 1 AND 4096 AND width*height<=16000000 AND size_bytes BETWEEN 1 AND 5242880)
) ENGINE=InnoDB;
CREATE TABLE product_image_cover (
 product_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 image_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 FOREIGN KEY(product_id) REFERENCES catalog_product(id), FOREIGN KEY(image_id) REFERENCES product_image_asset(id)
) ENGINE=InnoDB;
CREATE TABLE product_image_event (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 product_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_id INT NOT NULL,actor_username VARCHAR(64) NOT NULL,
 action VARCHAR(16) NOT NULL,image_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,alt VARCHAR(160) NULL,
 before_version INT NOT NULL,after_version INT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(product_id) REFERENCES catalog_product(id),FOREIGN KEY(actor_id) REFERENCES user(id),FOREIGN KEY(image_id) REFERENCES product_image_asset(id),
 CONSTRAINT ck_image_event CHECK(before_version>=0 AND after_version=before_version+1 AND ((action='UPLOAD' AND source_sha256 IS NOT NULL AND alt IS NOT NULL) OR (action='REMOVE' AND source_sha256 IS NULL AND alt IS NULL)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- No FK: reservation commits independently before an S3 PUT / main SQL transaction.
CREATE TABLE product_image_cleanup (
 object_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 not_before DATETIME(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)+INTERVAL 10 MINUTE),
 attempts INT NOT NULL DEFAULT 0,
 CONSTRAINT ck_image_cleanup CHECK(attempts>=0)
) ENGINE=InnoDB;
INSERT INTO permissions_backend(id,name,description,parent_id,level,url,method,admin_router,code) VALUES
 (54,'商品圖片','讀取主圖與操作紀錄',NULL,1,'/api/admin/products/*/image','GET',NULL,'catalog.images.read'),
 (55,'商品圖片內容','讀取私有主圖內容',NULL,1,'/api/admin/products/*/image/*','GET',NULL,'catalog.images.content'),
 (56,'上傳商品圖片','草稿主圖上傳與替換',NULL,1,'/api/admin/products/*/image','POST',NULL,'catalog.images.upload'),
 (57,'移除商品圖片','移除草稿主圖',NULL,1,'/api/admin/products/*/image','DELETE',NULL,'catalog.images.remove');
INSERT INTO role_permission VALUES(1,54),(1,55),(1,56),(1,57),(2,54),(2,55),(2,56),(2,57);
