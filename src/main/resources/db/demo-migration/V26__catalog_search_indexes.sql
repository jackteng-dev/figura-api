-- V1–V25 are immutable. Commerce V2 Phase 2: server-side catalog search.
-- Indexes only (no data change). The ngram full-text index serves Chinese/Japanese titles,
-- characters, series, brands and manufacturers kept in search_text by the application.
ALTER TABLE catalog_product
 ADD FULLTEXT INDEX ft_product_search(search_text) WITH PARSER ngram,
 ADD INDEX ix_product_brand(status,brand_id),
 ADD INDEX ix_product_work(status,work_id),
 ADD INDEX ix_product_category(status,category_id),
 ADD INDEX ix_product_release(status,release_date,release_month),
 ADD INDEX ix_product_type(status,product_type,scale);
