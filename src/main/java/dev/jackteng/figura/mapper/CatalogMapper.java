package dev.jackteng.figura.mapper;
import dev.jackteng.figura.pojo.CatalogProduct;
import dev.jackteng.figura.pojo.CatalogSku;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface CatalogMapper {
    String PUBLIC_WHERE="status='PUBLISHED' AND EXISTS (SELECT 1 FROM catalog_sku s WHERE s.product_id=catalog_product.id AND s.enabled=TRUE)";
    @Select("SELECT * FROM catalog_product WHERE id=#{id}") CatalogProduct find(String id);
    @Select("SELECT * FROM catalog_product WHERE id=#{id} FOR UPDATE") CatalogProduct lock(String id);
    @Select("SELECT * FROM catalog_product WHERE "+PUBLIC_WHERE+" AND id=#{id}") CatalogProduct publicFind(String id);
    @Select("<script>SELECT * FROM catalog_product <if test='publicOnly'>WHERE "+PUBLIC_WHERE+"</if> ORDER BY created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<CatalogProduct> list(@Param("publicOnly") boolean publicOnly,@Param("limit") int limit,@Param("offset") int offset);
    @Select("<script>SELECT COUNT(*) FROM catalog_product <if test='publicOnly'>WHERE "+PUBLIC_WHERE+"</if></script>") long count(boolean publicOnly);
    @Select("SELECT s.*,(i.quantity-i.reserved) AS quantity FROM catalog_sku s JOIN sku_inventory i ON i.sku_id=s.id WHERE s.product_id=#{id} ORDER BY s.code") List<CatalogSku> skus(String id);
    @Select("SELECT s.*,(i.quantity-i.reserved) AS quantity FROM catalog_sku s JOIN sku_inventory i ON i.sku_id=s.id WHERE s.product_id=#{id} ORDER BY s.code FOR UPDATE") List<CatalogSku> lockSkus(String id);
    @Insert("INSERT INTO catalog_product(id,title,brand,category,description,brand_id,category_id,work_id,character_name,manufacturer,scale,height_mm,dimensions,materials,product_type,edition,is_limited,accessories,release_date,release_month,spec_note,content_origin,concept_materials,search_text) VALUES(#{id},#{title},#{brand},#{category},#{description},#{brandId},#{categoryId},#{workId},#{characterName},#{manufacturer},#{scale},#{heightMm},#{dimensions},#{materials},#{productType},#{edition},#{isLimited},#{accessories},#{releaseDate},#{releaseMonth},#{specNote},#{contentOrigin},#{conceptMaterials},#{searchText})") void insert(CatalogProduct product);
    @Update("UPDATE catalog_product SET title=#{title},brand=#{brand},category=#{category},description=#{description},brand_id=#{brandId},category_id=#{categoryId},work_id=#{workId},character_name=#{characterName},manufacturer=#{manufacturer},scale=#{scale},height_mm=#{heightMm},dimensions=#{dimensions},materials=#{materials},product_type=#{productType},edition=#{edition},is_limited=#{isLimited},accessories=#{accessories},release_date=#{releaseDate},release_month=#{releaseMonth},spec_note=#{specNote},content_origin=#{contentOrigin},concept_materials=#{conceptMaterials},search_text=#{searchText},version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND version=#{version} AND status='DRAFT'") int update(CatalogProduct product);
    @Update("UPDATE catalog_product SET status=#{status},version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND version=#{version}")
    int state(@Param("id") String id,@Param("version") int version,@Param("status") String status);
    /** 商品頁的預購摘要：開放、未截止且款式啟用的批次；有剩餘配額者優先（與搜尋 OPEN_BATCH 同規則），全部額滿才回最早截止者。 */
    @Select("SELECT b.id AS batchId,b.opened_at AS openedAt,b.deadline_at AS deadlineAt,b.arrival_from AS arrivalFrom,b.arrival_to AS arrivalTo,b.price_twd AS priceTwd,(b.capacity-b.held_quantity-b.paid_quantity) AS remaining FROM preorder_batch b JOIN catalog_sku k ON k.id=b.sku_id AND k.product_id=b.product_id AND k.enabled=TRUE WHERE b.product_id=#{id} AND b.status='OPEN' AND b.deadline_at>CURRENT_TIMESTAMP(6) ORDER BY (b.capacity-b.held_quantity-b.paid_quantity>0) DESC,b.deadline_at,b.id LIMIT 1")
    java.util.Map<String,Object> openBatch(String id);
    @Insert("INSERT INTO catalog_sku(id,product_id,code,label,price_twd,enabled) VALUES(#{id},#{productId},#{code},#{label},#{priceTwd},#{enabled})") void insertSku(CatalogSku sku);
    @Update("UPDATE catalog_sku SET label=#{label},price_twd=#{priceTwd},enabled=#{enabled} WHERE id=#{id} AND product_id=#{productId}") int updateSku(CatalogSku sku);
}
