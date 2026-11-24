package dev.jackteng.figura.mapper;
import dev.jackteng.figura.pojo.*;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface GalleryMapper {
 record Entry(String imageId,String imageType,int position) {}
 @Select("SELECT image_id,image_type,position FROM product_image_gallery WHERE product_id=#{id} ORDER BY position,image_id") List<Entry> entries(String id);
 @Insert("INSERT INTO product_image_gallery(product_id,image_id,image_type,position) VALUES(#{product},#{image},#{type},#{position})") void add(@Param("product") String product,@Param("image") String image,@Param("type") String type,@Param("position") int position);
 @Delete("DELETE FROM product_image_gallery WHERE product_id=#{product} AND image_id=#{image}") void remove(@Param("product") String product,@Param("image") String image);
 @Update("UPDATE product_image_gallery SET position=#{position} WHERE product_id=#{product} AND image_id=#{image}") void position(@Param("product") String product,@Param("image") String image,@Param("position") int position);
 @Select("SELECT a.id,a.product_id,v.object_key,v.sha256,a.source_sha256,v.content_type,a.alt,v.width,v.height,v.size_bytes FROM product_image_variant v JOIN product_image_asset a ON a.id=v.image_id WHERE image_id=#{id} ORDER BY width") List<ProductImage> variants(String id);
 @Insert("INSERT INTO product_image_variant(image_id,width,height,object_key,sha256,content_type,size_bytes) VALUES(#{id},#{width},#{height},#{objectKey},#{sha256},#{contentType},#{sizeBytes})") void variant(ProductImage image);
 @Select("SELECT * FROM product_image_gallery_event WHERE request_id=#{id}") GalleryEvent request(String id);
 @Select("SELECT * FROM product_image_gallery_event WHERE product_id=#{id} ORDER BY created_at DESC,request_id DESC LIMIT 50") List<GalleryEvent> history(String id);
 @Insert("INSERT INTO product_image_command(request_id,product_id) VALUES(#{request},#{product})") void reserve(@Param("request") String request,@Param("product") String product);
 @Insert("INSERT INTO product_image_gallery_event(request_id,product_id,actor_id,actor_username,action,image_id,fingerprint,before_version,after_version) VALUES(#{requestId},#{productId},#{actorId},#{actorUsername},#{action},#{imageId},#{fingerprint},#{beforeVersion},#{afterVersion})") void append(GalleryEvent event);
}
