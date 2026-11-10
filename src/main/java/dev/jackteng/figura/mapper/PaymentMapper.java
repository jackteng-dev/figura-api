package dev.jackteng.figura.mapper;
import dev.jackteng.figura.pojo.*;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface PaymentMapper {
 @Select("SELECT * FROM mock_payment_event WHERE payment_id=#{id} ORDER BY created_at,event_id") List<MockPaymentEvent> history(String id);
 @Select("SELECT * FROM mock_refund_event WHERE payment_id=#{id}") RefundEvent refund(String id);
 @Select("SELECT COUNT(*) FROM mock_payment p WHERE p.order_id=#{id} AND p.status='REVIEW_REQUIRED' AND NOT EXISTS(SELECT 1 FROM mock_refund_event r WHERE r.payment_id=p.id AND r.action='REVIEW_REFUND')") int unresolvedReviews(String id);
 @Select("SELECT COUNT(*) FROM mock_payment_event WHERE payment_id=#{id} AND outcome='SUCCESS' AND disposition='REVIEW_REQUIRED'") int reviewSuccess(String id);
 @Select("SELECT COUNT(*) FROM mock_refund_event WHERE payment_id=#{id} AND action='REVIEW_REFUND'") int reviewResolved(String id);
 @Select("SELECT * FROM mock_payment WHERE id=#{id}") MockPayment find(String id);
 @Select("SELECT * FROM mock_payment WHERE id=#{id} FOR UPDATE") MockPayment lock(String id);
 @Select("SELECT * FROM mock_payment WHERE customer_id=#{customer} AND request_id=#{request}") MockPayment request(@Param("customer") String customer,@Param("request") String request);
 @Select("SELECT * FROM mock_payment WHERE order_id=#{order} ORDER BY created_at DESC,id DESC") List<MockPayment> list(String order);
 @Insert("INSERT INTO mock_payment(id,order_id,customer_id,request_id,transaction_id,expected_order_version,amount_twd) VALUES(#{id},#{orderId},#{customerId},#{requestId},#{transactionId},#{expectedOrderVersion},#{amountTwd})") void insert(MockPayment payment);
 @Update("UPDATE mock_payment SET status=#{status},settled_at=CURRENT_TIMESTAMP(6) WHERE id=#{id}") void state(@Param("id") String id,@Param("status") String status);
 @Select("SELECT * FROM mock_payment_event WHERE event_id=#{id}") MockPaymentEvent event(String id);
 @Insert("INSERT INTO mock_payment_event(event_id,payment_id,fingerprint,outcome,disposition) VALUES(#{eventId},#{paymentId},#{fingerprint},#{outcome},#{disposition})") void append(MockPaymentEvent event);
 @Update("UPDATE sku_inventory SET quantity=quantity-#{qty},reserved=reserved-#{qty},version=version+1 WHERE sku_id=#{sku} AND reserved>=#{qty} AND quantity>=#{qty}") int consume(@Param("sku") String sku,@Param("qty") int qty);
 @Update("UPDATE customer_order SET status='PAID',paid_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=#{id} AND status='PENDING_PAYMENT'") int paid(String id);
}
