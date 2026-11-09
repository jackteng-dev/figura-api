package com.shop.elpsy.mapper;
import com.shop.elpsy.pojo.*;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface FulfillmentMapper {
 @Select("SELECT * FROM customer_order WHERE id=#{id}") CustomerOrder find(String id);
 @Select("SELECT id,status,version,subtotal_twd,shipping_twd,fulfillment_status,created_at,paid_at FROM customer_order WHERE (#{status} IS NULL OR status=#{status}) AND (#{fulfillment} IS NULL OR fulfillment_status=#{fulfillment}) ORDER BY created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}") List<CustomerOrder> list(@Param("status") String status,@Param("fulfillment") String fulfillment,@Param("limit") int limit,@Param("offset") int offset);
 @Select("SELECT COUNT(*) FROM customer_order WHERE (#{status} IS NULL OR status=#{status}) AND (#{fulfillment} IS NULL OR fulfillment_status=#{fulfillment})") long count(@Param("status") String status,@Param("fulfillment") String fulfillment);
 @Select("SELECT username FROM user WHERE id=#{id} AND is_stop='F' FOR UPDATE") String lockActor(int id);
 @Select("SELECT p.id FROM user_role ur JOIN role r ON r.id=ur.role_id AND r.enabled=TRUE JOIN role_permission rp ON rp.role_id=ur.role_id JOIN permissions_backend p ON p.id=rp.permission_id WHERE ur.user_id=#{actor} AND p.code=#{code} FOR SHARE") List<Integer> lockPermission(@Param("actor") int actor,@Param("code") String code);
 @Select("SELECT * FROM order_fulfillment_event WHERE request_id=#{id}") FulfillmentEvent request(String id);
 @Select("SELECT * FROM order_fulfillment_event WHERE order_id=#{id} ORDER BY created_at,request_id") List<FulfillmentEvent> history(String id);
 @Insert("INSERT INTO order_fulfillment_event(request_id,order_id,actor_id,actor_username,action,note,before_version,after_version,before_status,after_status,tracking_id) VALUES(#{requestId},#{orderId},#{actorId},#{actorUsername},#{action},#{note},#{beforeVersion},#{afterVersion},#{beforeStatus},#{afterStatus},#{trackingId})") void append(FulfillmentEvent event);
 @Update("UPDATE customer_order SET fulfillment_status='SHIPPED',shipment_tracking_id=#{tracking},shipped_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=#{id} AND status='PAID' AND fulfillment_status='UNFULFILLED' AND version=#{version}") int ship(@Param("id") String id,@Param("tracking") String tracking,@Param("version") int version);
 @Update("UPDATE customer_order SET fulfillment_status='DELIVERED',delivered_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=#{id} AND status='PAID' AND fulfillment_status='SHIPPED' AND version=#{version}") int deliver(@Param("id") String id,@Param("version") int version);
}
