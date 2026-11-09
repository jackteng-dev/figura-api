package com.shop.elpsy.mapper;

import com.shop.elpsy.pojo.CartItem;
import com.shop.elpsy.pojo.CustomerAddress;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ShoppingMapper {
    @Select("SELECT id FROM customer WHERE id=#{id} AND enabled=TRUE FOR UPDATE") String lockCustomer(String id);
    @Insert("INSERT IGNORE INTO customer_cart(customer_id) VALUES(#{id})") void ensureCart(String id);
    @Select("SELECT version FROM customer_cart WHERE customer_id=#{id}") Integer cartVersion(String id);
    @Update("UPDATE customer_cart SET version=version+1 WHERE customer_id=#{id}") void bumpCart(String id);
    @Select("SELECT COUNT(*) FROM customer_cart_item WHERE customer_id=#{id}") int cartCount(String id);
    @Select("SELECT COUNT(*) FROM customer_cart_item WHERE customer_id=#{customer} AND sku_id=#{sku}") int contains(@Param("customer") String customer,@Param("sku") String sku);
    @Select("SELECT product_id FROM catalog_sku WHERE id=#{sku}") String productForSku(String sku);
    String ITEM_SELECT="SELECT ci.sku_id,ci.quantity,ci.title_snapshot,ci.label_snapshot,s.product_id,s.label,s.price_twd,s.enabled,p.title,p.status,(i.quantity-i.reserved) AS stock FROM customer_cart_item ci JOIN catalog_sku s ON s.id=ci.sku_id JOIN catalog_product p ON p.id=s.product_id JOIN sku_inventory i ON i.sku_id=s.id WHERE ci.customer_id=#{id} ORDER BY ci.sku_id";
    @Select(ITEM_SELECT) List<CartItem> items(String id);
    @Insert("INSERT INTO customer_cart_item(customer_id,sku_id,quantity,title_snapshot,label_snapshot) VALUES(#{customer},#{sku},#{quantity},#{title},#{label}) ON DUPLICATE KEY UPDATE quantity=#{quantity},title_snapshot=#{title},label_snapshot=#{label}")
    void setItem(@Param("customer") String customer,@Param("sku") String sku,@Param("quantity") int quantity,@Param("title") String title,@Param("label") String label);
    @Delete("DELETE FROM customer_cart_item WHERE customer_id=#{customer} AND sku_id=#{sku}") int removeItem(@Param("customer") String customer,@Param("sku") String sku);
    @Select("SELECT * FROM customer_address WHERE customer_id=#{customer} AND deleted=FALSE ORDER BY id") List<CustomerAddress> addresses(String customer);
    @Select("SELECT * FROM customer_address WHERE id=#{id}") CustomerAddress address(String id);
    @Select("SELECT COUNT(*) FROM customer_address WHERE customer_id=#{customer} AND deleted=FALSE") int addressCount(String customer);
    @Insert("INSERT INTO customer_address(id,customer_id,recipient_name,phone,postal_code,city,district,street) VALUES(#{id},#{customerId},#{recipientName},#{phone},#{postalCode},#{city},#{district},#{street})") void insertAddress(CustomerAddress address);
    @Update("UPDATE customer_address SET recipient_name=#{recipientName},phone=#{phone},postal_code=#{postalCode},city=#{city},district=#{district},street=#{street},version=version+1 WHERE id=#{id} AND customer_id=#{customerId} AND version=#{version} AND deleted=FALSE") int updateAddress(CustomerAddress address);
    @Update("UPDATE customer_address SET deleted=TRUE,version=version+1,recipient_name='',phone='',postal_code='',city='',district='',street='' WHERE id=#{id} AND customer_id=#{customer} AND version=#{version} AND deleted=FALSE") int deleteAddress(@Param("customer") String customer,@Param("id") String id,@Param("version") int version);
}
