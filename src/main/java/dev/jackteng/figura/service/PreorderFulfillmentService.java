package dev.jackteng.figura.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.figura.dto.CatalogPayloads.Page;
import dev.jackteng.figura.dto.PreorderFulfillmentPayloads.*;
import dev.jackteng.figura.dto.PreorderOrderPayloads.View;
import dev.jackteng.figura.mapper.FulfillmentMapper;
import dev.jackteng.figura.pojo.CustomUserDetails;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Dedicated preorder supply; never reads or writes sku_inventory or stock fulfillment. */
@Service
public class PreorderFulfillmentService {
 private final JdbcTemplate db; private final ObjectMapper json; private final PreorderOrderService orders;
 private final FulfillmentMapper auth; private final NotificationCapture notifications; private final boolean mockEnabled;
 public PreorderFulfillmentService(JdbcTemplate db,ObjectMapper json,PreorderOrderService orders,FulfillmentMapper auth,NotificationCapture notifications,@Value("${shop.fulfillment.mock-enabled:false}") boolean mockEnabled){this.db=db;this.json=json;this.orders=orders;this.auth=auth;this.notifications=notifications;this.mockEnabled=mockEnabled;}
 private ResponseStatusException fail(int code,String message){return new ResponseStatusException(HttpStatus.valueOf(code),message);}
 private void uuid(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(Exception e){throw fail(400,"預購編號不正確");}}
 private String note(String note){var n=note.trim();if(n.isBlank())throw fail(400,"到貨／履約原因不可空白");return n;}
 private OffsetDateTime time(ResultSet r,String key)throws SQLException{var t=r.getObject(key,LocalDateTime.class);return t==null?null:t.atOffset(ZoneOffset.ofHours(8));}
 private final RowMapper<Supply> supplies=(r,n)->new Supply(r.getString("id"),r.getString("name"),r.getString("status"),r.getInt("version"),r.getInt("capacity"),r.getInt("received_quantity"),r.getInt("allocated_quantity"),r.getInt("shipped_quantity"),r.getInt("received_quantity")-r.getInt("allocated_quantity"),r.getInt("received_quantity")-r.getInt("shipped_quantity"));
 private final RowMapper<Event> events=(r,n)->new Event(r.getString("request_id"),r.getString("batch_id"),r.getString("order_id"),r.getInt("actor_id"),r.getString("actor_username"),r.getString("action"),r.getString("fingerprint"),r.getString("note"),r.getInt("quantity"),r.getInt("received_before"),r.getInt("received_after"),r.getInt("allocated_before"),r.getInt("allocated_after"),r.getInt("shipped_before"),r.getInt("shipped_after"),r.getInt("batch_version_before"),r.getInt("batch_version_after"),(Integer)r.getObject("order_version_before"),(Integer)r.getObject("order_version_after"),r.getString("tracking_number"),time(r,"created_at"));
 private Supply supply(String id,boolean lock){uuid(id);var found=db.query("SELECT * FROM preorder_batch WHERE id=?"+(lock?" FOR UPDATE":""),supplies,id);if(found.isEmpty())throw fail(404,"找不到預購批次");return found.get(0);}
 @Transactional(readOnly=true) public Page<Supply> list(int page,int size){if(page<1||page>100000||size<1||size>50)throw fail(400,"分頁參數不正確");return new Page<>(db.query("SELECT * FROM preorder_batch ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",supplies,size,(page-1)*size),page,size,db.queryForObject("SELECT COUNT(*) FROM preorder_batch",Long.class));}
 @Transactional(readOnly=true) public BatchDetail batchDetail(String id){return new BatchDetail(supply(id,false),db.query("SELECT * FROM preorder_fulfillment_event WHERE batch_id=? ORDER BY created_at,request_id",events,id));}
 private boolean paid(View o){return o.status().equals("PAID")&&!o.paymentReviewRequired();}
 private boolean allocatable(View o,Supply s){return paid(o)&&o.arrivalStatus().equals("NOT_ARRIVED")&&o.fulfillmentStatus().equals("UNFULFILLED")&&o.fulfillment().allocatedQuantity()==0&&s.unallocatedQuantity()>=o.quantity();}
 private boolean shippable(View o){return mockEnabled&&paid(o)&&o.arrivalStatus().equals("ARRIVED")&&o.fulfillment().allocatedQuantity()==o.quantity()&&o.fulfillmentStatus().equals("UNFULFILLED");}
 private boolean deliverable(View o){return mockEnabled&&paid(o)&&o.fulfillmentStatus().equals("SHIPPED");}
 @Transactional(readOnly=true) public Detail detail(String id){var o=orders.staffDetail(id);return new Detail(supply(o.batchId(),false),mockEnabled,allocatable(o,supply(o.batchId(),false)),shippable(o),deliverable(o),db.query("SELECT * FROM preorder_fulfillment_event WHERE order_id=? ORDER BY created_at,request_id",events,id));}
 private String authorize(CustomUserDetails actor,String permission){String name=actor==null?null:auth.lockActor(actor.getId());if(name==null||auth.lockPermission(actor.getId(),permission).isEmpty())throw fail(403,"員工或預購到貨／履約授權已失效");return name;}
 private String hash(Object v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(v)));}catch(Exception e){throw new IllegalStateException(e);}}
 private Event replay(String request,String batch,String order,String action,String fingerprint,CustomUserDetails actor){var old=db.query("SELECT * FROM preorder_fulfillment_event WHERE request_id=?",events,request);if(old.isEmpty())return null;var e=old.get(0);if(e.actorId()!=actor.getId()||!e.batchId().equals(batch)||!Objects.equals(e.orderId(),order)||!e.action().equals(action)||!e.fingerprint().equals(fingerprint))throw fail(409,"到貨／履約操作編號已用於不同內容");return e;}
 private Event append(String request,String action,String fingerprint,String note,int quantity,Supply before,View old,CustomUserDetails actor,String username){
  var after=supply(before.batchId(),false);var order=old==null?null:orders.staffDetail(old.id());
  try{db.update("INSERT INTO preorder_fulfillment_event(request_id,batch_id,order_id,actor_id,actor_username,action,fingerprint,note,quantity,received_before,received_after,allocated_before,allocated_after,shipped_before,shipped_after,batch_version_before,batch_version_after,order_version_before,order_version_after,tracking_number) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",request,before.batchId(),old==null?null:old.id(),actor.getId(),username,action,fingerprint,note,quantity,before.receivedQuantity(),after.receivedQuantity(),before.allocatedQuantity(),after.allocatedQuantity(),before.shippedQuantity(),after.shippedQuantity(),before.version(),after.version(),old==null?null:old.version(),order==null?null:order.version(),order==null?null:order.fulfillment().trackingNumber());}catch(DuplicateKeyException e){throw fail(409,"到貨／履約操作編號或訂單操作已保存，請核對");}
  if(order!=null){String customer=db.queryForObject("SELECT customer_id FROM preorder_order WHERE id=?",String.class,order.id());notifications.enqueuePreorder(customer,order,"PREORDER_"+(action.equals("ALLOCATE")?"ALLOCATED":action.equals("SHIP")?"SHIPPED":"DELIVERED"),request);}
  return db.queryForObject("SELECT * FROM preorder_fulfillment_event WHERE request_id=?",events,request);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public Received receive(String id,Receive in,CustomUserDetails actor){
  String username=authorize(actor,"preorders.arrivals.receive"),note=note(in.note());var before=supply(id,true);String fingerprint=hash(List.of("RECEIVE",id,in.expectedBatchVersion(),in.quantity(),note));var retry=replay(in.requestId(),id,null,"RECEIVE",fingerprint,actor);if(retry!=null)return new Received(retry,batchDetail(id),true);
  if(before.version()!=in.expectedBatchVersion())throw fail(409,"預購批次版本已變更，請先重讀");
  if(before.status().equals("DRAFT"))throw fail(409,"草稿批次不能登記到貨");
  if(in.quantity()>before.capacity()-before.receivedQuantity())throw fail(409,"累計到貨不可超過批次供應配額");
  db.update("UPDATE preorder_batch SET received_quantity=received_quantity+?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",in.quantity(),id);
  return new Received(append(in.requestId(),"RECEIVE",fingerprint,note,in.quantity(),before,null,actor,username),batchDetail(id),false);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public Applied allocate(String id,Allocate in,CustomUserDetails actor){return operate(id,"ALLOCATE",in.requestId(),in.expectedOrderVersion(),in.expectedBatchVersion(),in.note(),actor);}
 @Transactional(isolation=Isolation.READ_COMMITTED) public Applied command(String id,String action,Command in,CustomUserDetails actor){if(!Set.of("SHIP","DELIVER").contains(action))throw fail(400,"不支援的履約操作");if(!mockEnabled)throw fail(404,"預購模擬履約未啟用");return operate(id,action,in.requestId(),in.expectedOrderVersion(),null,in.note(),actor);}
 private Applied operate(String id,String action,String request,int expected,Integer expectedBatch,String rawNote,CustomUserDetails actor){
  String username=authorize(actor,action.equals("ALLOCATE")?"preorder-orders.allocate":action.equals("SHIP")?"preorder-orders.ship.mock":"preorder-orders.deliver.mock"),note=note(rawNote);uuid(id);
  var owners=db.queryForList("SELECT customer_id FROM preorder_order WHERE id=?",String.class,id);if(owners.isEmpty())throw fail(404,"找不到預購訂單");var locked=orders.lockForMoney(owners.get(0),id,false);var o=locked.order();var before=supply(o.batchId(),false);
  String fingerprint=hash(Arrays.asList(action,id,expected,expectedBatch,note));var retry=replay(request,o.batchId(),id,action,fingerprint,actor);if(retry!=null)return new Applied(retry,o,detail(id),true);
  if(o.version()!=expected||(expectedBatch!=null&&before.version()!=expectedBatch))throw fail(409,"預購訂單或到貨批次版本已變更，請先重讀");
  if(action.equals("ALLOCATE")){
   if(!allocatable(o,before))throw fail(409,"只可整筆分配給已付款、未分配且無待核對訂單，實際到貨數量須足夠");
   db.update("UPDATE preorder_batch SET allocated_quantity=allocated_quantity+?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",o.quantity(),o.batchId());
   db.update("UPDATE preorder_order SET allocated_quantity=quantity,allocated_at=CURRENT_TIMESTAMP(6),arrival_status='ARRIVED',version=version+1 WHERE id=?",id);
  }else if(action.equals("SHIP")){
   if(!shippable(o)||before.shippedQuantity()+o.quantity()>before.allocatedQuantity())throw fail(409,"出貨前需已付款、整筆已分配、未出貨且無待核對付款");
   db.update("UPDATE preorder_batch SET shipped_quantity=shipped_quantity+?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",o.quantity(),o.batchId());
   db.update("UPDATE preorder_order SET fulfillment_status='SHIPPED',tracking_number=?,shipped_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=?","mock_pre_"+UUID.randomUUID(),id);
  }else{
   if(!deliverable(o))throw fail(409,"只有已出貨、已付款且無待核對付款才可模擬送達");
   db.update("UPDATE preorder_batch SET version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",o.batchId());
   db.update("UPDATE preorder_order SET fulfillment_status='DELIVERED',delivered_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=?",id);
  }
  var event=append(request,action,fingerprint,note,o.quantity(),before,o,actor,username);return new Applied(event,orders.staffDetail(id),detail(id),false);
 }
}
