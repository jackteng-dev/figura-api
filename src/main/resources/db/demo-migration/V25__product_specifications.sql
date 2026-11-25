-- V1–V24 are immutable. Commerce V2 Phase 1: figure product specifications.
-- Additive only: every new column is NULL (= not provided) or has a default, so the previous
-- release keeps working on this schema. Nothing is invented for existing products: only the
-- content origin is derived from the existing AI-concept description prefix, and the search
-- text is built from fields that already exist.
ALTER TABLE catalog_product
 ADD COLUMN character_name VARCHAR(120) NULL,
 ADD COLUMN manufacturer VARCHAR(80) NULL,
 ADD COLUMN scale VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN height_mm INT NULL,
 ADD COLUMN dimensions VARCHAR(160) NULL,
 ADD COLUMN materials VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN product_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN edition VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN is_limited BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN accessories VARCHAR(1000) NULL,
 ADD COLUMN release_date DATE NULL,
 ADD COLUMN release_month CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN spec_note VARCHAR(2000) NULL,
 ADD COLUMN content_origin VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'REAL',
 ADD COLUMN concept_materials VARCHAR(160) NULL,
 ADD COLUMN search_text VARCHAR(1000) NULL,
 ADD CONSTRAINT ck_product_scale CHECK(scale IS NULL OR scale IN ('1/1','1/2','1/3','1/4','1/5','1/6','1/7','1/8','1/10','1/12','1/20','1/35','1/60','1/100','1/144','NON_SCALE')),
 ADD CONSTRAINT ck_product_height CHECK(height_mm IS NULL OR height_mm BETWEEN 1 AND 5000),
 ADD CONSTRAINT ck_product_type CHECK(product_type IS NULL OR product_type IN ('PREPAINTED','ACTION_FIGURE','MODEL_KIT','GARAGE_KIT','PRIZE','BLIND_BOX','STATUE','PLUSH','OTHER')),
 ADD CONSTRAINT ck_product_edition CHECK(edition IS NULL OR edition IN ('STANDARD','LIMITED','BONUS','DELUXE')),
 ADD CONSTRAINT ck_product_release CHECK((release_date IS NULL OR release_month IS NULL) AND (release_month IS NULL OR release_month REGEXP '^20[0-9]{2}-(0[1-9]|1[0-2])$')),
 ADD CONSTRAINT ck_product_origin CHECK(content_origin IN ('REAL','AI_CONCEPT') AND (content_origin='AI_CONCEPT' OR concept_materials IS NULL));

UPDATE catalog_product SET content_origin='AI_CONCEPT' WHERE description LIKE 'AI 生成概念圖｜展示商品%';

UPDATE catalog_product p
  JOIN catalog_brand b ON b.id=p.brand_id
  LEFT JOIN catalog_work w ON w.id=p.work_id
   SET p.search_text=LEFT(CONCAT_WS(' ',p.title,w.name,b.name),1000);
