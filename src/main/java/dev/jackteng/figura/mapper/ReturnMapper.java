package dev.jackteng.figura.mapper;
import dev.jackteng.figura.pojo.*;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface ReturnMapper {
 @Select("SELECT * FROM mock_order_return WHERE order_id=#{id}") OrderReturn find(String id);
 @Select("SELECT * FROM mock_order_return WHERE request_id=#{id}") OrderReturn request(String id);
 @Select("SELECT COUNT(*) FROM mock_order_return WHERE order_id=#{id} AND status IN ('REQUESTED','RECEIVED')") int active(String id);
 @Insert("INSERT INTO mock_order_return(order_id,request_id,customer_id,expected_order_version,reason) VALUES(#{orderId},#{requestId},#{customerId},#{expectedOrderVersion},#{reason})") void insert(OrderReturn r);
 @Select("SELECT * FROM order_return_event WHERE request_id=#{id}") ReturnEvent event(String id);
 @Select("SELECT * FROM order_return_event WHERE order_id=#{id} ORDER BY created_at,request_id") List<ReturnEvent> history(String id);
 @Insert("INSERT INTO order_return_event(request_id,order_id,actor_id,actor_username,action,note,before_version,after_version,before_status,after_status,disposition) VALUES(#{requestId},#{orderId},#{actorId},#{actorUsername},#{action},#{note},#{beforeVersion},#{afterVersion},#{beforeStatus},#{afterStatus},#{disposition})") void append(ReturnEvent e);
 @Update("UPDATE mock_order_return SET status='RECEIVED',disposition=#{disposition},received_at=CURRENT_TIMESTAMP(6) WHERE order_id=#{id} AND status='REQUESTED'") int receive(@Param("id") String id,@Param("disposition") String disposition);
 @Update("UPDATE mock_order_return SET status='REJECTED',rejected_at=CURRENT_TIMESTAMP(6) WHERE order_id=#{id} AND status='REQUESTED'") int reject(String id);
 @Update("UPDATE mock_order_return SET status='REFUNDED',refunded_at=CURRENT_TIMESTAMP(6) WHERE order_id=#{id} AND status='RECEIVED'") int refund(String id);
}
