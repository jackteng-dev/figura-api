CREATE TABLE catalog_brand (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 name VARCHAR(80) COLLATE utf8mb4_0900_as_cs NOT NULL UNIQUE,
 description VARCHAR(500) NOT NULL DEFAULT '', enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version INT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT ck_brand_name CHECK(CHAR_LENGTH(TRIM(name))>0),
 CONSTRAINT ck_brand_version CHECK(version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE catalog_category LIKE catalog_brand;
CREATE TABLE catalog_work LIKE catalog_brand;
CREATE TABLE catalog_master_guard(id INT PRIMARY KEY, CONSTRAINT ck_master_guard CHECK(id=1));
INSERT INTO catalog_master_guard VALUES(1);
CREATE TABLE catalog_master_event (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 actor_id INT NOT NULL, actor_username VARCHAR(64) NOT NULL,
 kind VARCHAR(16) NOT NULL, action VARCHAR(16) NOT NULL,
 target_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 before_state JSON NULL, after_state JSON NOT NULL, note VARCHAR(500) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(actor_id) REFERENCES user(id),
 CONSTRAINT ck_master_event_kind CHECK(kind IN ('BRAND','WORK','CATEGORY')),
 CONSTRAINT ck_master_event_action CHECK(action IN ('CREATE','UPDATE')),
 INDEX ix_master_event_created(created_at,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Preserve historical label columns and product versions. Keep case/accent-distinct names separate.
INSERT INTO catalog_brand(id,name)
 SELECT UUID(),names.name FROM (SELECT DISTINCT TRIM(brand) COLLATE utf8mb4_0900_as_cs AS name FROM catalog_product WHERE CHAR_LENGTH(TRIM(brand))>0) names;
INSERT INTO catalog_category(id,name)
 SELECT UUID(),names.name FROM (SELECT DISTINCT TRIM(category) COLLATE utf8mb4_0900_as_cs AS name FROM catalog_product WHERE CHAR_LENGTH(TRIM(category))>0) names;
-- Minimal demo bootstrap; no guessed WORK assignment and no fake master audit for a migration.
INSERT INTO catalog_brand(id,name) SELECT UUID(),'ELPSY' WHERE NOT EXISTS(SELECT 1 FROM catalog_brand WHERE name='ELPSY');
INSERT INTO catalog_category(id,name) SELECT UUID(),'公仔' WHERE NOT EXISTS(SELECT 1 FROM catalog_category WHERE name='公仔');
ALTER TABLE catalog_product
 ADD COLUMN brand_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN category_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN work_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
UPDATE catalog_product p JOIN catalog_brand b ON b.name=TRIM(p.brand) COLLATE utf8mb4_0900_as_cs
 JOIN catalog_category c ON c.name=TRIM(p.category) COLLATE utf8mb4_0900_as_cs
 SET p.brand_id=b.id,p.category_id=c.id;
-- Invalid blank legacy labels fail this step; original databases require preflight/isolated restore first.
ALTER TABLE catalog_product
 MODIFY brand_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 MODIFY category_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ADD CONSTRAINT fk_product_brand FOREIGN KEY(brand_id) REFERENCES catalog_brand(id),
 ADD CONSTRAINT fk_product_category FOREIGN KEY(category_id) REFERENCES catalog_category(id),
 ADD CONSTRAINT fk_product_work FOREIGN KEY(work_id) REFERENCES catalog_work(id);
INSERT INTO permissions_backend(id,name,description,parent_id,level,url,method,admin_router,code) VALUES
 (200,'品牌作品分類','唯讀商品主檔',NULL,1,'/api/admin/masters','GET','/admin/masters','masters.read'),
 (201,'主檔選項','商品主檔選擇與狀態',NULL,1,'/api/admin/masters/options','GET',NULL,'masters.options'),
 (202,'新增主檔','新增品牌、作品或分類',NULL,1,'/api/admin/masters/*','POST',NULL,'masters.create'),
 (203,'編輯主檔','版本核對、啟停與異動稽核',NULL,1,'/api/admin/masters/*/*','PUT',NULL,'masters.update'),
 (204,'主檔稽核','唯讀歷史與操作核對',NULL,1,'/api/admin/masters/audit','GET',NULL,'masters.audit');
INSERT INTO role_permission VALUES(1,200),(1,201),(1,202),(1,203),(1,204),(2,200),(2,201),(2,204);
