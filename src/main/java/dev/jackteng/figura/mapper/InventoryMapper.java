package dev.jackteng.figura.mapper;
import dev.jackteng.figura.pojo.InventoryBalance;
import dev.jackteng.figura.pojo.InventoryAdjustment;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface InventoryMapper {
    String FIND="SELECT i.* FROM sku_inventory i JOIN catalog_sku s ON s.id=i.sku_id WHERE s.product_id=#{productId} AND i.sku_id=#{skuId}";
    @Insert("INSERT INTO sku_inventory(sku_id) VALUES(#{id})") void create(String id);
    @Select(FIND) InventoryBalance find(@Param("productId") String productId,@Param("skuId") String skuId);
    @Select(FIND+" FOR UPDATE") InventoryBalance lock(@Param("productId") String productId,@Param("skuId") String skuId);
    @Update("UPDATE sku_inventory SET quantity=#{quantity},version=version+1 WHERE sku_id=#{skuId} AND version=#{version}") int update(InventoryBalance balance);
    @Select("SELECT * FROM inventory_adjustment WHERE request_id=#{id}") InventoryAdjustment adjustment(String id);
    @Insert("INSERT INTO inventory_adjustment(request_id,sku_id,actor_id,actor_username,delta,reason,note,before_quantity,after_quantity,before_version,after_version) VALUES(#{requestId},#{skuId},#{actorId},#{actorUsername},#{delta},#{reason},#{note},#{beforeQuantity},#{afterQuantity},#{beforeVersion},#{afterVersion})") void append(InventoryAdjustment entry);
    @Select("SELECT * FROM inventory_adjustment WHERE sku_id=#{skuId} ORDER BY created_at DESC,request_id DESC LIMIT #{limit} OFFSET #{offset}")
    List<InventoryAdjustment> history(@Param("skuId") String skuId,@Param("limit") int limit,@Param("offset") int offset);
    @Select("SELECT COUNT(*) FROM inventory_adjustment WHERE sku_id=#{skuId}") long count(String skuId);
}
