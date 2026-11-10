package dev.jackteng.figura.mapper;
import dev.jackteng.figura.pojo.*;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface ImageMapper {
 @Select("SELECT a.* FROM product_image_cover c JOIN product_image_asset a ON a.id=c.image_id WHERE c.product_id=#{id}") ProductImage cover(String id);
 @Select("SELECT * FROM product_image_asset WHERE id=#{id}") ProductImage asset(String id);
 @Insert("INSERT INTO product_image_asset(id,product_id,object_key,sha256,source_sha256,content_type,alt,width,height,size_bytes) VALUES(#{id},#{productId},#{objectKey},#{sha256},#{sourceSha256},#{contentType},#{alt},#{width},#{height},#{sizeBytes})") void insert(ProductImage i);
 @Insert("INSERT INTO product_image_cover(product_id,image_id) VALUES(#{product},#{image}) ON DUPLICATE KEY UPDATE image_id=#{image}") void bind(@Param("product") String product,@Param("image") String image);
 @Delete("DELETE FROM product_image_cover WHERE product_id=#{id}") void unbind(String id);
 @Update("UPDATE catalog_product SET version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND version=#{version} AND status='DRAFT'") int bump(@Param("id") String id,@Param("version") int version);
 @Select("SELECT * FROM product_image_event WHERE request_id=#{id}") ImageEvent request(String id);
 @Select("SELECT * FROM product_image_event WHERE product_id=#{id} ORDER BY created_at DESC,request_id DESC LIMIT 50") List<ImageEvent> history(String id);
 @Insert("INSERT INTO product_image_event(request_id,product_id,actor_id,actor_username,action,image_id,source_sha256,alt,before_version,after_version) VALUES(#{requestId},#{productId},#{actorId},#{actorUsername},#{action},#{imageId},#{sourceSha256},#{alt},#{beforeVersion},#{afterVersion})") void append(ImageEvent e);
 @Insert("INSERT INTO product_image_cleanup(object_key) VALUES(#{key}) ON DUPLICATE KEY UPDATE object_key=object_key") void queue(String key);
 @Select("SELECT object_key FROM product_image_cleanup WHERE object_key=#{key} FOR UPDATE") String lockQueue(String key);
 @Select("SELECT object_key FROM product_image_cleanup WHERE not_before<=CURRENT_TIMESTAMP(6) ORDER BY not_before,object_key LIMIT 1 FOR UPDATE SKIP LOCKED") String nextCleanup();
 @Select("SELECT COUNT(*) FROM product_image_cover c JOIN product_image_asset a ON a.id=c.image_id WHERE a.object_key=#{key}") int referenced(String key);
 @Delete("DELETE FROM product_image_cleanup WHERE object_key=#{key}") void unqueue(String key);
 @Update("UPDATE product_image_cleanup SET attempts=attempts+1,not_before=CURRENT_TIMESTAMP(6)+INTERVAL 5 MINUTE WHERE object_key=#{key}") void retry(String key);
}
