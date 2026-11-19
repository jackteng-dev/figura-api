-- Additive gallery; V1-V23 and the legacy cover/event contract stay intact.
CREATE TABLE product_image_gallery (
 product_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 image_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 image_type VARCHAR(16) NOT NULL,
 position INT NOT NULL,
 FOREIGN KEY(product_id) REFERENCES catalog_product(id),
 FOREIGN KEY(image_id) REFERENCES product_image_asset(id),
 INDEX ix_gallery_product(product_id,position,image_id),
 CONSTRAINT ck_gallery CHECK(position>=0 AND image_type IN ('MAIN','ANGLE','FACE','DETAIL','SCENE','REAR'))
) ENGINE=InnoDB;
CREATE TABLE product_image_variant (
 image_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 width INT NOT NULL,height INT NOT NULL,
 object_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 content_type VARCHAR(32) NOT NULL,size_bytes INT NOT NULL,
 PRIMARY KEY(image_id,width),FOREIGN KEY(image_id) REFERENCES product_image_asset(id),
 CONSTRAINT ck_variant CHECK(width BETWEEN 1 AND 4096 AND height BETWEEN 1 AND 4096 AND size_bytes BETWEEN 1 AND 5242880 AND content_type IN ('image/png','image/jpeg'))
) ENGINE=InnoDB;
-- Common reservation makes request IDs unique across legacy and new image commands.
CREATE TABLE product_image_command (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 product_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 FOREIGN KEY(product_id) REFERENCES catalog_product(id)
) ENGINE=InnoDB;
INSERT INTO product_image_command(request_id,product_id) SELECT request_id,product_id FROM product_image_event;
CREATE TABLE product_image_gallery_event (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 product_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_id INT NOT NULL,actor_username VARCHAR(64) NOT NULL,
 action VARCHAR(16) NOT NULL,image_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 before_version INT NOT NULL,after_version INT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(request_id) REFERENCES product_image_command(request_id),
 FOREIGN KEY(product_id) REFERENCES catalog_product(id),FOREIGN KEY(actor_id) REFERENCES user(id),
 FOREIGN KEY(image_id) REFERENCES product_image_asset(id),
 CONSTRAINT ck_gallery_event CHECK(before_version>=0 AND after_version=before_version+1 AND action IN ('ADD','REPLACE','REMOVE','COVER','REORDER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
