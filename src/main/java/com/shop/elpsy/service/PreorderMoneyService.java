package com.shop.elpsy.service;

import com.shop.elpsy.dto.PreorderMoneyPayloads.*;
import com.shop.elpsy.dto.PreorderOrderPayloads.View;
import com.shop.elpsy.dto.PaymentPayloads.Start;
import com.shop.elpsy.dto.PaymentPayloads.Simulate;
import com.shop.elpsy.dto.PaymentPayloads.Notification;
import com.shop.elpsy.mapper.FulfillmentMapper;
import com.shop.elpsy.pojo.CustomUserDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Dedicated preorder money domain. Reuses only the stateless MOCK signing/validation gateway. */
@Service
public class PreorderMoneyService {
 private final JdbcTemplate db; private final PreorderOrderService orders; private final MockPaymentGateway gateway;
 private final PreorderFulfillmentService fulfillment; private final NotificationCapture notifications; private final FulfillmentMapper staff; private final boolean refundsEnabled;
 public PreorderMoneyService(JdbcTemplate db,PreorderOrderService orders,MockPaymentGateway gateway,NotificationCapture notifications,FulfillmentMapper staff,PreorderFulfillmentService fulfillment,@Value("${shop.refunds.mock-enabled:false}") boolean refundsEnabled){this.fulfillment=fulfillment;this.db=db;this.orders=orders;this.gateway=gateway;this.notifications=notifications;this.staff=staff;this.refundsEnabled=refundsEnabled;}
 private record P(String id,String order,String customer,String request,int expected,String transaction,long amount,String status,OffsetDateTime created,OffsetDateTime settled) {}
 private ResponseStatusException fail(int code,String message){return new ResponseStatusException(HttpStatus.valueOf(code),message);}
 private void uuid(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(Exception e){throw fail(400,"預購付款／訂單編號不正確");}}
 private OffsetDateTime time(ResultSet r,String key)throws SQLException{var t=r.getObject(key,LocalDateTime.class);return t==null?null:t.atOffset(ZoneOffset.ofHours(8));}
 private LocalDateTime now(){return db.queryForObject("SELECT CURRENT_TIMESTAMP(6)",LocalDateTime.class);}
 private final RowMapper<P> payments=(r,n)->new P(r.getString("id"),r.getString("order_id"),r.getString("customer_id"),r.getString("request_id"),r.getInt("expected_order_version"),r.getString("transaction_id"),r.getLong("amount_twd"),r.getString("status"),time(r,"created_at"),time(r,"settled_at"));
 private final RowMapper<Audit> audits=(r,n)->new Audit(r.getString("request_id"),r.getString("order_id"),r.getString("payment_id"),r.getInt("actor_id"),r.getString("actor_username"),r.getString("action"),r.getString("note"),r.getLong("amount_twd"),r.getInt("before_version"),r.getInt("after_version"),time(r,"created_at"));
 private P payment(String id,boolean lock){uuid(id);var found=db.query("SELECT * FROM preorder_mock_payment WHERE id=?"+(lock?" FOR UPDATE":""),payments,id);if(found.isEmpty())throw fail(404,"找不到預購付款");return found.get(0);}
 private List<P> all(String id){return db.query("SELECT * FROM preorder_mock_payment WHERE order_id=? ORDER BY created_at,id",payments,id);}
 private String owner(String id){uuid(id);var found=db.queryForList("SELECT customer_id FROM preorder_order WHERE id=?",String.class,id);if(found.isEmpty())throw fail(404,"找不到預購訂單");return found.get(0);}
 private RefundProgress refunded(String id){var found=db.query("SELECT amount_twd,created_at FROM preorder_refund_event WHERE payment_id=?",(r,n)->new RefundProgress("MOCK",r.getLong(1),time(r,"created_at")),id);return found.isEmpty()?null:found.get(0);}
 private Payment view(P p){return new Payment(p.id(),p.order(),p.status(),p.amount(),"TWD","MOCK",p.created(),p.settled(),refunded(p.id()));}
 private boolean canStart(View o,List<P> all){return gateway.enabled()&&o.status().equals("PENDING")&&o.expiresAt().toLocalDateTime().isAfter(now())&&!o.paymentReviewRequired()&&all.size()<20&&all.stream().allMatch(p->p.status().equals("FAILED")||(p.status().equals("REVIEW_REQUIRED")&&refunded(p.id())!=null));}
 private Options options(View o){var all=all(o.id());return new Options(gateway.enabled(),canStart(o,all),all.stream().map(this::view).toList());}
 @Transactional(readOnly=true) public Options options(String customer,String id){return options(orders.detail(customer,id));}
 @Transactional(isolation=Isolation.READ_COMMITTED) public Started start(String customer,String id,Start in){
  gateway.requireEnabled();var o=orders.lockForMoney(customer,id,true).order();var prior=db.query("SELECT * FROM preorder_mock_payment WHERE customer_id=? AND request_id=?",payments,customer,in.requestId());
  if(!prior.isEmpty()){var p=prior.get(0);if(!p.order().equals(id)||p.expected()!=in.expectedOrderVersion())throw fail(409,"付款操作編號已用於不同內容");return new Started(view(p),true);}
  if(o.version()!=in.expectedOrderVersion())throw fail(409,"預購訂單版本已變更，請先重讀");if(!canStart(o,all(id)))throw fail(409,"目前不可建立新預購付款，請核對既有付款與保留期限");
  String pid=UUID.randomUUID().toString();db.update("INSERT INTO preorder_mock_payment(id,order_id,customer_id,request_id,expected_order_version,transaction_id,amount_twd) VALUES(?,?,?,?,?,?,?)",pid,id,customer,in.requestId(),in.expectedOrderVersion(),"mock_"+pid,o.totalTwd());return new Started(view(payment(pid,false)),false);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public Receipt simulate(String customer,String pid,Simulate in){gateway.requireEnabled();var p=payment(pid,false);if(!p.customer().equals(customer))throw fail(404,"找不到本人預購付款");orders.lockForMoney(customer,p.order(),true);var notice=new Notification(in.eventId(),pid,p.transaction(),p.amount(),"TWD",in.outcome());String raw=gateway.raw(notice),ts=Long.toString(Instant.now().getEpochSecond());return process(gateway.verify(ts,gateway.sign(ts,raw),raw));}
 @Transactional(isolation=Isolation.READ_COMMITTED) public Receipt receive(String ts,String signature,String raw){return process(gateway.verify(ts,signature,raw));}
 private Receipt receipt(String event,String disposition,P p,boolean replay){return new Receipt(event,disposition,replay,view(payment(p.id(),false)),orders.detail(p.customer(),p.order()));}
 private void state(String id,String status){db.update("UPDATE preorder_mock_payment SET status=?,settled_at=CURRENT_TIMESTAMP(6) WHERE id=?",status,id);}
 private int number(Map<String,Object> b,String key){return ((Number)b.get(key)).intValue();}
 private void quota(P p,View o,Map<String,Object> before,String action,String source,Integer actor){
  int held=number(before,"held_quantity"),paid=number(before,"paid_quantity"),dh=action.equals("PAY")?-o.quantity():0,dp=action.equals("PAY")?o.quantity():-o.quantity();
  String fingerprint;try{fingerprint=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((action+":"+source).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
  db.update("INSERT INTO preorder_order_event(id,request_id,customer_id,order_id,batch_id,actor_kind,action,fingerprint,quantity,held_delta,held_before,held_after,paid_before,paid_after,order_version,batch_version,paid_delta,payment_id,actor_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),UUID.randomUUID().toString(),p.customer(),o.id(),o.batchId(),actor==null?"SYSTEM":"STAFF",action,fingerprint,o.quantity(),dh,held,held+dh,paid,paid+dp,o.version(),number(before,"version")+1,dp,p.id(),actor);
 }
 private Receipt process(Notification in){
  var known=payment(in.paymentId(),false);var locked=orders.lockForMoney(known.customer(),known.order(),false);var o=locked.order();var p=payment(known.id(),true);
  if(!p.transaction().equals(in.transactionId())||p.amount()!=in.amountTwd()||!"TWD".equals(in.currency()))throw fail(409,"通知交易或金額與預購付款快照不符");
  String fingerprint=gateway.fingerprint(in);var prior=db.queryForList("SELECT payment_id,fingerprint,disposition FROM preorder_mock_payment_event WHERE event_id=?",in.eventId());
  if(!prior.isEmpty()){var e=prior.get(0);if(!p.id().equals(e.get("payment_id"))||!fingerprint.equals(e.get("fingerprint")))throw fail(409,"通知編號已用於不同內容");return receipt(in.eventId(),(String)e.get("disposition"),p,true);}
  String disposition,kind=null;
  if(p.status().equals("SUCCEEDED"))disposition="ALREADY_SUCCEEDED";
  else if(p.status().equals("REVIEW_REQUIRED"))disposition="REVIEW_REQUIRED";
  else if(in.outcome().equals("FAILURE")){if(p.status().equals("FAILED"))disposition="ALREADY_FAILED";else{state(p.id(),"FAILED");disposition="PAYMENT_FAILED";kind="PREORDER_PAYMENT_FAILED";}}
  else {
   if(o.status().equals("PENDING")&&!o.expiresAt().toLocalDateTime().isAfter(now())){orders.expire(p.customer(),p.order());o=orders.detail(p.customer(),p.order());}
   if(p.status().equals("FAILED")||!o.status().equals("PENDING")||o.paymentReviewRequired()){
    state(p.id(),"REVIEW_REQUIRED");db.update("UPDATE preorder_order SET version=version+1 WHERE id=?",o.id());disposition="REVIEW_REQUIRED";kind="PREORDER_PAYMENT_REVIEW_REQUIRED";
   }else{
    if(db.update("UPDATE preorder_batch SET held_quantity=held_quantity-?,paid_quantity=paid_quantity+?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND held_quantity>=?",o.quantity(),o.quantity(),o.batchId(),o.quantity())!=1)throw new IllegalStateException("Preorder payment quota mismatch");
    db.update("UPDATE preorder_order SET status='PAID',paid_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=?",o.id());state(p.id(),"SUCCEEDED");disposition="APPLIED";kind="PREORDER_PAYMENT_SUCCEEDED";quota(p,orders.detail(p.customer(),o.id()),locked.batch(),"PAY",in.eventId(),null);
   }
  }
  try{db.update("INSERT INTO preorder_mock_payment_event(event_id,payment_id,fingerprint,outcome,disposition) VALUES(?,?,?,?,?)",in.eventId(),p.id(),fingerprint,in.outcome(),disposition);}catch(DuplicateKeyException e){throw fail(409,"通知編號已被其他预購付款使用");}
  if(kind!=null)notifications.enqueuePreorder(p.customer(),orders.detail(p.customer(),p.order()),kind,p.id());return receipt(in.eventId(),disposition,p,false);
 }
 private boolean refundable(View o){return refundsEnabled&&o.status().equals("PAID")&&o.arrivalStatus().equals("NOT_ARRIVED")&&o.fulfillmentStatus().equals("UNFULFILLED")&&!o.paymentReviewRequired();}
 @Transactional(readOnly=true) public Detail detail(String id){var o=orders.staffDetail(id);return new Detail(o,options(o),refundsEnabled,refundable(o),all(id).stream().filter(p->p.status().equals("REVIEW_REQUIRED")).map(p->new Review(p.id(),p.amount(),refunded(p.id())!=null,db.query("SELECT event_id,outcome,disposition,created_at FROM preorder_mock_payment_event WHERE payment_id=? ORDER BY created_at,event_id",(r,n)->new Evidence(r.getString(1),r.getString(2),r.getString(3),time(r,"created_at")),p.id()))).toList(),db.query("SELECT * FROM preorder_refund_event WHERE order_id=? ORDER BY created_at,request_id",audits,id),fulfillment.detail(id));}
 @Transactional(isolation=Isolation.READ_COMMITTED) public Applied refund(String id,String reviewPayment,Refund in,CustomUserDetails actor){
  if(!refundsEnabled)throw fail(404,"預購模擬退款未啟用");uuid(id);if(reviewPayment!=null)uuid(reviewPayment);String action=reviewPayment==null?"ORDER_REFUND":"REVIEW_REFUND",note=in.note().trim();if(note.isBlank())throw fail(400,"退款核對原因不可空白");
  String username=actor==null?null:staff.lockActor(actor.getId());if(username==null||staff.lockPermission(actor.getId(),reviewPayment==null?"preorder-orders.refund.mock":"preorder-orders.review.mock").isEmpty())throw fail(403,"員工或預購退款授權已失效");
  String customer=owner(id);var locked=orders.lockForMoney(customer,id,false);var o=locked.order();var previous=db.query("SELECT * FROM preorder_refund_event WHERE request_id=?",audits,in.requestId());
  if(!previous.isEmpty()){var e=previous.get(0);if(e.actorId()!=actor.getId()||!e.orderId().equals(id)||!e.action().equals(action)||e.beforeVersion()!=in.expectedOrderVersion()||!e.note().equals(note)||(reviewPayment!=null&&!e.paymentId().equals(reviewPayment)))throw fail(409,"退款操作編號已用於不同內容");return new Applied(e,detail(id),true);}
  if(o.version()!=in.expectedOrderVersion())throw fail(409,"預購訂單版本已變更，請先重讀");P p;
  if(reviewPayment==null){if(!refundable(o))throw fail(409,"只可退款已付款、未到貨未出貨且無待核對付款的預購");var paid=all(id).stream().filter(x->x.status().equals("SUCCEEDED")).toList();if(paid.size()!=1)throw fail(409,"預購付款紀錄需先核對");p=payment(paid.get(0).id(),true);}
  else {p=payment(reviewPayment,true);if(!p.order().equals(id))throw fail(404,"找不到此預購的待核對付款");if(!p.status().equals("REVIEW_REQUIRED")||refunded(p.id())!=null)throw fail(409,"付款沒有未處理的核對狀態");if(db.queryForObject("SELECT COUNT(*) FROM preorder_mock_payment_event WHERE payment_id=? AND outcome='SUCCESS' AND disposition='REVIEW_REQUIRED'",Integer.class,p.id())<1)throw fail(409,"缺少原待核對成功通知");}
  if(p.amount()!=o.totalTwd())throw fail(409,"預購付款金額與訂單快照不一致");
  if(reviewPayment==null){
   if(db.update("UPDATE preorder_batch SET paid_quantity=paid_quantity-?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND paid_quantity>=?",o.quantity(),o.batchId(),o.quantity())!=1)throw fail(409,"預購已付配額不一致，請先核對");
   db.update("UPDATE preorder_order SET status='REFUNDED',closed_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=?",id);quota(p,orders.detail(customer,id),locked.batch(),"REFUND",in.requestId(),actor.getId());
  }else db.update("UPDATE preorder_order SET version=version+1 WHERE id=?",id);
  try{db.update("INSERT INTO preorder_refund_event(request_id,order_id,payment_id,actor_id,actor_username,action,note,amount_twd,before_version,after_version) VALUES(?,?,?,?,?,?,?,?,?,?)",in.requestId(),id,p.id(),actor.getId(),username,action,note,p.amount(),o.version(),o.version()+1);}catch(DuplicateKeyException e){throw fail(409,"退款操作編號或付款已處理");}
  notifications.enqueuePreorder(customer,orders.detail(customer,id),reviewPayment==null?"PREORDER_REFUNDED":"PREORDER_REVIEW_REFUNDED",p.id());return new Applied(db.queryForObject("SELECT * FROM preorder_refund_event WHERE request_id=?",audits,in.requestId()),detail(id),false);
 }
}
