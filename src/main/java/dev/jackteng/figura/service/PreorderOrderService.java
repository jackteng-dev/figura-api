package dev.jackteng.figura.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.figura.dto.CatalogPayloads.Page;
import dev.jackteng.figura.dto.OrderPayloads.Shipping;
import dev.jackteng.figura.dto.PreorderOrderPayloads.*;
import dev.jackteng.figura.mapper.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;

@Service
public class PreorderOrderService {
 private static final ZoneOffset TAIPEI=ZoneOffset.ofHours(8);
 private final JdbcTemplate db; private final ObjectMapper json; private final ShoppingMapper shopping;
 private final CatalogMapper catalog; private final CatalogMasterService masters; private final NotificationCapture notifications;
 public PreorderOrderService(JdbcTemplate db,ObjectMapper json,ShoppingMapper shopping,CatalogMapper catalog,CatalogMasterService masters,NotificationCapture notifications){this.db=db;this.json=json;this.shopping=shopping;this.catalog=catalog;this.masters=masters;this.notifications=notifications;}
 private ResponseStatusException fail(int code,String message){return new ResponseStatusException(HttpStatus.valueOf(code),message);}
 private void uuid(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(Exception e){throw fail(400,"預購訂單 ID 不正確");}}
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
 private String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 private OffsetDateTime time(ResultSet r,String key)throws SQLException{var t=r.getObject(key,LocalDateTime.class);return t==null?null:t.atOffset(TAIPEI);}
 private LocalDateTime now(){return db.queryForObject("SELECT CURRENT_TIMESTAMP(6)",LocalDateTime.class);}
 private Shipping shipping(String value){try{return json.readValue(value,Shipping.class);}catch(Exception e){throw new IllegalStateException(e);}}
 private boolean review(String id){return db.queryForObject("SELECT COUNT(*) FROM preorder_mock_payment p LEFT JOIN preorder_refund_event f ON f.payment_id=p.id WHERE p.order_id=? AND p.status='REVIEW_REQUIRED' AND f.request_id IS NULL",Integer.class,id)>0;}
 private dev.jackteng.figura.dto.PreorderMoneyPayloads.RefundProgress refund(String id){var found=db.query("SELECT amount_twd,created_at FROM preorder_refund_event WHERE order_id=? AND action='ORDER_REFUND'",(r,n)->new dev.jackteng.figura.dto.PreorderMoneyPayloads.RefundProgress("MOCK",r.getLong(1),time(r,"created_at")),id);return found.isEmpty()?null:found.get(0);}
 private final RowMapper<View> rows=(r,n)->new View(r.getString("id"),r.getString("batch_id"),r.getString("batch_name"),r.getString("product_title"),r.getString("sku_label"),r.getInt("unit_price_twd"),r.getInt("quantity"),r.getLong("subtotal_twd"),r.getInt("shipping_twd"),r.getLong("subtotal_twd")+r.getInt("shipping_twd"),shipping(r.getString("shipping")),time(r,"deadline_at"),r.getObject("arrival_from",LocalDate.class),r.getObject("arrival_to",LocalDate.class),r.getString("status"),r.getInt("version"),time(r,"created_at"),time(r,"expires_at"),time(r,"closed_at"),time(r,"paid_at"),r.getString("arrival_status"),r.getString("fulfillment_status"),review(r.getString("id")),refund(r.getString("id")),new dev.jackteng.figura.dto.PreorderFulfillmentPayloads.Progress(r.getInt("allocated_quantity"),time(r,"allocated_at"),"MOCK",r.getString("tracking_number"),time(r,"shipped_at"),time(r,"delivered_at")));
 private View find(String customer,String id,boolean lock){uuid(id);var found=db.query("SELECT * FROM preorder_order WHERE customer_id=? AND id=?"+(lock?" FOR UPDATE":""),rows,customer,id);if(found.isEmpty())throw fail(404,"找不到預購訂單");return found.get(0);}
 @Transactional(readOnly=true) public View detail(String customer,String id){return find(customer,id,false);}
 public record Locked(View order,Map<String,Object> batch) {}
 @Transactional(propagation=Propagation.MANDATORY) public Locked lockForMoney(String customer,String id,boolean enabled){owner(customer,enabled);var reference=find(customer,id,false);var b=batch(reference.batchId(),true);return new Locked(find(customer,id,true),b);}
 @Transactional(readOnly=true) public View staffDetail(String id){uuid(id);var found=db.query("SELECT customer_id FROM preorder_order WHERE id=?",(r,n)->r.getString(1),id);if(found.isEmpty())throw fail(404,"找不到預購訂單");return find(found.get(0),id,false);}
 @Transactional(readOnly=true) public Page<dev.jackteng.figura.dto.PreorderMoneyPayloads.Summary> staffList(int page,int size,String status){if(page<1||page>100000||size<1||size>50)throw fail(400,"分頁參數不正確");if(status!=null&&!Set.of("PENDING","PAID","REFUNDED","CANCELLED","EXPIRED").contains(status))throw fail(400,"訂單狀態不正確");return new Page<>(db.query("SELECT * FROM preorder_order WHERE (? IS NULL OR status=?) ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",rows,status,status,size,(page-1)*size).stream().map(o->new dev.jackteng.figura.dto.PreorderMoneyPayloads.Summary(o.id(),o.batchId(),o.batchName(),o.productTitle(),o.skuLabel(),o.quantity(),o.totalTwd(),o.status(),o.version(),o.createdAt(),o.paidAt(),o.paymentReviewRequired())).toList(),page,size,db.queryForObject("SELECT COUNT(*) FROM preorder_order WHERE (? IS NULL OR status=?)",Long.class,status,status));}
 @Transactional(readOnly=true) public Page<View> list(String customer,int page,int size){if(page<1||page>100000||size<1||size>50)throw fail(400,"分頁參數不正確");return new Page<>(db.query("SELECT * FROM preorder_order WHERE customer_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",rows,customer,size,(page-1)*size),page,size,db.queryForObject("SELECT COUNT(*) FROM preorder_order WHERE customer_id=?",Long.class,customer));}
 private void owner(String customer,boolean requireEnabled){var ids=db.queryForList("SELECT id FROM customer WHERE id=?"+(requireEnabled?" AND enabled=TRUE":"")+" FOR UPDATE",String.class,customer);if(ids.isEmpty())throw fail(401,"顧客登入狀態無效");}
 private Map<String,Object> batch(String id,boolean lock){uuid(id);var found=db.queryForList("SELECT * FROM preorder_batch WHERE id=?"+(lock?" FOR UPDATE":""),id);if(found.isEmpty())throw fail(404,"找不到預購批次");return found.get(0);}
 private int number(Map<String,Object> b,String key){return ((Number)b.get(key)).intValue();}
 private LocalDateTime dateTime(Map<String,Object> b,String key){var v=b.get(key);return v instanceof Timestamp t?t.toLocalDateTime():(LocalDateTime)v;}
 private LocalDate date(Map<String,Object> b,String key){var v=b.get(key);return v instanceof java.sql.Date d?d.toLocalDate():(LocalDate)v;}
 // The owner lock serializes request IDs across that customer's different batches/orders.
 private Applied replay(String customer,String request,String fingerprint){var found=db.queryForList("SELECT order_id,fingerprint FROM preorder_order_event WHERE customer_id=? AND request_id=?",customer,request);if(found.isEmpty())return null;var e=found.get(0);if(!fingerprint.equals(e.get("fingerprint")))throw fail(409,"requestId 已用於不同預購操作");return new Applied(find(customer,(String)e.get("order_id"),false),true);}
 private void append(String customer,View order,Map<String,Object> before,String action,String request,String fingerprint,String actor,int delta){
  int held=number(before,"held_quantity"),paid=number(before,"paid_quantity");String event=UUID.randomUUID().toString();
  db.update("INSERT INTO preorder_order_event(id,request_id,customer_id,order_id,batch_id,actor_kind,action,fingerprint,quantity,held_delta,held_before,held_after,paid_before,paid_after,order_version,batch_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",event,request,customer,order.id(),order.batchId(),actor,action,fingerprint,order.quantity(),delta,held,held+delta,paid,paid,order.version(),number(before,"version")+1);
  notifications.enqueuePreorder(customer,order,"PREORDER_"+(action.equals("PLACE")?"PLACED":action.equals("CANCEL")?"CANCELLED":"EXPIRED"),event);
 }
 /** Placement: customer -> master guard -> product -> batch. Never acquires physical inventory. */
 @Transactional(isolation=Isolation.READ_COMMITTED) public Applied place(String customer,Place in){
  owner(customer,true);String fingerprint=hash(List.of("PLACE",in.batchId(),in.quantity(),in.addressId(),in.addressVersion(),in.expectedSubtotalTwd()));var old=replay(customer,in.requestId(),fingerprint);if(old!=null)return old;
  masters.lock();var reference=batch(in.batchId(),false);var product=catalog.lock((String)reference.get("product_id"));var b=batch(in.batchId(),true);
  if(!"OPEN".equals(b.get("status")))throw fail(409,"批次尚未開放或已截單");
  if(product==null||!"PUBLISHED".equals(product.getStatus()))throw fail(409,"預購商品已下架");masters.publishable(product);
  if(catalog.skus(product.getId()).stream().noneMatch(s->s.getId().equals(b.get("sku_id"))&&s.isEnabled()))throw fail(409,"預購款式已停用");
  var at=now();var deadline=dateTime(b,"deadline_at");if(!deadline.isAfter(at))throw fail(409,"批次已到截止時間");
  int quantity=in.quantity(),price=number(b,"price_twd");long subtotal=(long)price*quantity;
  if(in.expectedSubtotalTwd()!=subtotal)throw fail(409,"預購金額已變更，請重新核對");
  if(quantity>number(b,"capacity")-number(b,"held_quantity")-number(b,"paid_quantity"))throw fail(409,"批次配額不足，請減少數量或重新核對");
  var address=shopping.address(in.addressId());if(address==null||!customer.equals(address.getCustomerId())||address.isDeleted())throw fail(404,"找不到本人地址");if(address.getVersion()!=in.addressVersion())throw fail(409,"地址已變更，請重新核對");
  var shipping=new Shipping(address.getRecipientName(),address.getPhone(),address.getPostalCode(),address.getCity(),address.getDistrict(),address.getStreet());
  var expires=at.plusMinutes(30).isBefore(deadline)?at.plusMinutes(30):deadline;String id=UUID.randomUUID().toString();
  db.update("INSERT INTO preorder_order(id,customer_id,batch_id,batch_name,product_title,sku_label,unit_price_twd,quantity,subtotal_twd,shipping,deadline_at,arrival_from,arrival_to,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,customer,in.batchId(),b.get("name"),b.get("product_title"),b.get("sku_label"),price,quantity,subtotal,encode(shipping),deadline,date(b,"arrival_from"),date(b,"arrival_to"),at,expires);
  if(db.update("UPDATE preorder_batch SET held_quantity=held_quantity+?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND held_quantity+paid_quantity+?<=capacity",quantity,in.batchId(),quantity)!=1)throw fail(409,"批次配額已變更，請重新核對");
  var order=find(customer,id,false);append(customer,order,b,"PLACE",in.requestId(),fingerprint,"CUSTOMER",quantity);return new Applied(order,false);
 }
 private View close(String customer,View order,Map<String,Object> b,String action,String request,String fingerprint,String actor){
  if(db.update("UPDATE preorder_batch SET held_quantity=held_quantity-?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND held_quantity>=?",order.quantity(),order.batchId(),order.quantity())!=1)throw new IllegalStateException("Preorder quota ledger mismatch");
  db.update("UPDATE preorder_order SET status=?,closed_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=?",action.equals("CANCEL")?"CANCELLED":"EXPIRED",order.id());var after=find(customer,order.id(),false);append(customer,after,b,action,request,fingerprint,actor,-order.quantity());return after;
 }
 /** Release: customer -> batch -> order. Works after closure, unpublication or disabled owner. */
 @Transactional(isolation=Isolation.READ_COMMITTED) public Applied cancel(String customer,String id,Cancel in){
  uuid(id);owner(customer,true);String fingerprint=hash(List.of("CANCEL",id,in.expectedVersion()));var old=replay(customer,in.requestId(),fingerprint);if(old!=null)return old;
  var reference=find(customer,id,false);var b=batch(reference.batchId(),true);var order=find(customer,id,true);
  if(!order.status().equals("PENDING"))throw fail(409,"訂單已結束，請重新核對");if(order.version()!=in.expectedVersion())throw fail(409,"訂單版本已變更，請重新核對");
  return new Applied(close(customer,order,b,order.expiresAt().toLocalDateTime().isAfter(now())?"CANCEL":"EXPIRE",in.requestId(),fingerprint,"CUSTOMER"),false);
 }
 public record Due(String customerId,String id) {}
 @Transactional(readOnly=true) public List<Due> due(){return db.query("SELECT customer_id,id FROM preorder_order WHERE status='PENDING' AND expires_at<=CURRENT_TIMESTAMP(6) ORDER BY expires_at,id LIMIT 100",(r,n)->new Due(r.getString(1),r.getString(2)));}
 @Transactional(isolation=Isolation.READ_COMMITTED) public void expire(String customer,String id){
  owner(customer,false);var reference=find(customer,id,false);var b=batch(reference.batchId(),true);var order=find(customer,id,true);
  if(!order.status().equals("PENDING")||order.expiresAt().toLocalDateTime().isAfter(now()))return;
  close(customer,order,b,"EXPIRE",UUID.randomUUID().toString(),hash(List.of("EXPIRE",id)),"SYSTEM");
 }
}
