package com.shop.elpsy.mapper;
import com.shop.elpsy.pojo.*;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface OrderMapper {
 @Select("SELECT * FROM customer_order WHERE customer_id=#{customer} AND request_id=#{request}") CustomerOrder request(@Param("customer") String customer,@Param("request") String request);
 @Select("SELECT * FROM customer_order WHERE customer_id=#{customer} AND id=#{id} FOR UPDATE") CustomerOrder lock(@Param("customer") String customer,@Param("id") String id);
 @Select("SELECT * FROM customer_order WHERE customer_id=#{customer} AND id=#{id}") CustomerOrder find(@Param("customer") String customer,@Param("id") String id);
 @Select("SELECT * FROM customer_order WHERE customer_id=#{customer} ORDER BY created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}") List<CustomerOrder> list(@Param("customer") String customer,@Param("limit") int limit,@Param("offset") int offset);
 @Select("SELECT COUNT(*) FROM customer_order WHERE customer_id=#{customer}") long count(String customer);
 @Select("SELECT COUNT(*) FROM customer_order WHERE customer_id=#{customer} AND status='PENDING_PAYMENT'") int pending(String customer);
 @Select("SELECT CURRENT_TIMESTAMP(6)") java.time.LocalDateTime now();
 @Insert("INSERT INTO customer_order(id,customer_id,request_id,cart_version,address_id,address_version,subtotal_twd,recipient_name,phone,postal_code,city,district,street,expires_at) VALUES(#{id},#{customerId},#{requestId},#{cartVersion},#{addressId},#{addressVersion},#{subtotalTwd},#{recipientName},#{phone},#{postalCode},#{city},#{district},#{street},#{expiresAt})") void insert(CustomerOrder order);
 @Insert("INSERT INTO customer_order_line(order_id,sku_id,product_id,title,label,quantity,unit_price_twd) VALUES(#{orderId},#{skuId},#{productId},#{title},#{label},#{quantity},#{unitPriceTwd})") void insertLine(CustomerOrderLine line);
 @Select("SELECT * FROM customer_order_line WHERE order_id=#{id} ORDER BY product_id,sku_id") List<CustomerOrderLine> lines(String id);
 @Update("UPDATE sku_inventory SET reserved=reserved+#{qty},version=version+1 WHERE sku_id=#{sku} AND quantity-reserved>=#{qty}") int reserve(@Param("sku") String sku,@Param("qty") int qty);
 @Update("UPDATE sku_inventory SET reserved=reserved-#{qty},version=version+1 WHERE sku_id=#{sku} AND reserved>=#{qty}") int release(@Param("sku") String sku,@Param("qty") int qty);
 @Insert("INSERT INTO order_stock_event(order_id,sku_id,action,quantity) VALUES(#{order},#{sku},#{action},#{qty})") void event(@Param("order") String order,@Param("sku") String sku,@Param("action") String action,@Param("qty") int qty);
 @Update("UPDATE customer_order SET status=#{state},version=version+1,closed_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND status='PENDING_PAYMENT'") int close(@Param("id") String id,@Param("state") String state);
 @Delete("DELETE FROM customer_cart_item WHERE customer_id=#{customer}") void clearCart(String customer);
 @Select("SELECT * FROM customer_order WHERE status='PENDING_PAYMENT' AND expires_at<=CURRENT_TIMESTAMP(6) ORDER BY expires_at,id LIMIT 100") List<CustomerOrder> due();
 // Enabled-account checks are for user writes; expiry must also release a disabled customer's holds.
 @Select("SELECT id FROM customer WHERE id=#{id} FOR UPDATE") String lockOwner(String id);
}
