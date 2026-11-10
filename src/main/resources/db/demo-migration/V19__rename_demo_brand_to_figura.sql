-- 2026-11-10: the product was renamed ELPSY Shop -> Figura. Rename the V13 bootstrap demo brand (data only).
-- Keeps the brand id, so product brand_id references stay valid; skipped if a 'Figura' brand already exists.
UPDATE catalog_product p JOIN catalog_brand b ON b.id=p.brand_id
   SET p.brand='Figura'
 WHERE b.name='ELPSY' AND NOT EXISTS(SELECT 1 FROM (SELECT 1 FROM catalog_brand WHERE name='Figura') f);
UPDATE catalog_brand SET name='Figura', version=version+1, updated_at=CURRENT_TIMESTAMP(6)
 WHERE name='ELPSY' AND NOT EXISTS(SELECT 1 FROM (SELECT 1 FROM catalog_brand WHERE name='Figura') f);
