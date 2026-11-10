package dev.jackteng.figura.mapper;
import dev.jackteng.figura.pojo.RefundEvent;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface RefundMapper {
 @Select("SELECT * FROM mock_refund_event WHERE request_id=#{id}") RefundEvent request(String id);
 @Select("SELECT * FROM mock_refund_event WHERE payment_id=#{id}") RefundEvent payment(String id);
 @Select("SELECT * FROM mock_refund_event WHERE order_id=#{id} ORDER BY created_at,request_id") List<RefundEvent> history(String id);
 @Select("SELECT * FROM mock_refund_event WHERE order_id=#{id} AND action IN ('ORDER_REFUND','RETURN_REFUND')") RefundEvent orderRefund(String id);
 @Insert("INSERT INTO mock_refund_event(request_id,order_id,payment_id,actor_id,actor_username,action,note,amount_twd,before_version,after_version) VALUES(#{requestId},#{orderId},#{paymentId},#{actorId},#{actorUsername},#{action},#{note},#{amountTwd},#{beforeVersion},#{afterVersion})") void append(RefundEvent event);
 @Update("UPDATE sku_inventory SET quantity=quantity+#{qty},version=version+1 WHERE sku_id=#{sku} AND quantity<=1000000-#{qty}") int restock(@Param("sku") String sku,@Param("qty") int qty);
 @Update("UPDATE customer_order SET status='REFUNDED',closed_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=#{id} AND status='PAID' AND fulfillment_status='UNFULFILLED' AND version=#{version}") int refund(@Param("id") String id,@Param("version") int version);
 @Update("UPDATE customer_order SET status='REFUNDED',return_refunded=TRUE,closed_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=#{id} AND status='PAID' AND fulfillment_status IN ('SHIPPED','DELIVERED') AND version=#{version}") int refundReturn(@Param("id") String id,@Param("version") int version);
 @Update("UPDATE customer_order SET version=version+1 WHERE id=#{id} AND version=#{version}") int reviewed(@Param("id") String id,@Param("version") int version);
}
