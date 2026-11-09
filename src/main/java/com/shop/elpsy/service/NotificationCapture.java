package com.shop.elpsy.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.elpsy.pojo.CustomerOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
@Service
public class NotificationCapture {
 private final JdbcTemplate db;private final ObjectMapper json;
 public NotificationCapture(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
 @Transactional(propagation=Propagation.MANDATORY)
 public void enqueuePreorder(String customer,com.shop.elpsy.dto.PreorderOrderPayloads.View order,String kind,String source){
  if(!Set.of("PREORDER_PLACED","PREORDER_CANCELLED","PREORDER_EXPIRED","PREORDER_PAYMENT_SUCCEEDED","PREORDER_PAYMENT_FAILED","PREORDER_PAYMENT_REVIEW_REQUIRED","PREORDER_REFUNDED","PREORDER_REVIEW_REFUNDED","PREORDER_ALLOCATED","PREORDER_SHIPPED","PREORDER_DELIVERED").contains(kind))throw new IllegalArgumentException("Unknown preorder event");
  String payload;try{payload=json.writeValueAsString(Map.of("orderId",order.id(),"eventKind",kind,"orderVersion",order.version(),"orderStatus",order.status(),"fulfillmentStatus",order.fulfillmentStatus(),"totalTwd",order.totalTwd()));}catch(Exception e){throw new IllegalStateException(e);}
  db.update("INSERT INTO notification_outbox(id,event_kind,source_id,preorder_order_id,recipient_ref,payload) VALUES(?,?,?,?,?,?)",UUID.randomUUID().toString(),kind,source,order.id(),customer,payload);
 }
 /** Must join the business transaction, after its owner/order/product locks. No delivery I/O. */
 @Transactional(propagation=Propagation.MANDATORY)
 public void enqueue(CustomerOrder order,String kind,String source){
  if(!Set.of("ORDER_PLACED","ORDER_CANCELLED","ORDER_EXPIRED","PAYMENT_SUCCEEDED","PAYMENT_FAILED","PAYMENT_REVIEW_REQUIRED","ORDER_SHIPPED","ORDER_DELIVERED","ORDER_REFUNDED","REVIEW_REFUNDED","RETURN_REQUESTED","RETURN_RECEIVED","RETURN_REJECTED").contains(kind))throw new IllegalArgumentException("Unknown notification event");
  String payload;try{payload=json.writeValueAsString(Map.of("orderId",order.getId(),"eventKind",kind,"orderVersion",order.getVersion(),"orderStatus",order.getStatus(),"fulfillmentStatus",order.getFulfillmentStatus(),"totalTwd",order.getSubtotalTwd()+order.getShippingTwd()));}catch(Exception e){throw new IllegalStateException(e);}
  db.update("INSERT INTO notification_outbox(id,event_kind,source_id,order_id,recipient_ref,payload) VALUES(?,?,?,?,?,?)",UUID.randomUUID().toString(),kind,source,order.getId(),order.getCustomerId(),payload);
 }
}
