package com.shop.elpsy.service;
import com.shop.elpsy.dto.PaymentPayloads.*;
import com.shop.elpsy.mapper.*;
import com.shop.elpsy.pojo.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
@Service
public class PaymentService {
 private final PaymentMapper payments;private final OrderMapper orders;private final ShoppingMapper shopping;private final CatalogMapper catalog;private final OrderService orderService;private final MockPaymentGateway gateway;
 private final NotificationCapture notifications;
 public PaymentService(PaymentMapper payments,OrderMapper orders,ShoppingMapper shopping,CatalogMapper catalog,OrderService orderService,MockPaymentGateway gateway,NotificationCapture notifications){this.notifications=notifications;this.payments=payments;this.orders=orders;this.shopping=shopping;this.catalog=catalog;this.orderService=orderService;this.gateway=gateway;}
 private ResponseStatusException conflict(String m){return new ResponseStatusException(HttpStatus.CONFLICT,m);}
 private ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"找不到付款或本人訂單");}
 private void uuid(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"付款編號不正確");}}
 private View view(MockPayment p){var refund=payments.refund(p.getId());return new View(p.getId(),p.getOrderId(),p.getStatus(),p.getAmountTwd(),p.getCurrency(),"MOCK",p.getCreatedAt(),p.getSettledAt(),refund==null?null:new com.shop.elpsy.dto.RefundPayloads.Progress("REFUNDED","MOCK",refund.getAmountTwd(),refund.getCreatedAt()));}
 private boolean canStart(CustomerOrder o,List<MockPayment> all){return "PENDING_PAYMENT".equals(o.getStatus())&&o.getExpiresAt().isAfter(orders.now())&&all.size()<20&&all.stream().allMatch(p->"FAILED".equals(p.getStatus())||("REVIEW_REQUIRED".equals(p.getStatus())&&payments.reviewResolved(p.getId())==1));}
 @Transactional(readOnly=true) public Options options(String customer,String id){uuid(id);var o=orders.find(customer,id);if(o==null)throw missing();if(!gateway.enabled())return new Options(false,false,List.of());var all=payments.list(id);return new Options(true,canStart(o,all),all.stream().map(this::view).toList());}
 @Transactional(isolation=Isolation.READ_COMMITTED) public Started start(String customer,String id,Start input){
  gateway.requireEnabled();uuid(id);if(shopping.lockCustomer(customer)==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"顧客登入狀態無效");var o=orders.lock(customer,id);if(o==null)throw missing();
  var prior=payments.request(customer,input.requestId());if(prior!=null){if(!prior.getOrderId().equals(id)||prior.getExpectedOrderVersion()!=input.expectedOrderVersion())throw conflict("付款操作編號已用於不同內容");return new Started(view(prior),true);}
  if(o.getVersion()!=input.expectedOrderVersion())throw conflict("訂單已變更，請重新核對");
  if(!canStart(o,payments.list(id)))throw conflict("目前不可建立新付款，請核對既有付款或訂單狀態");
  var p=new MockPayment();p.setId(UUID.randomUUID().toString());p.setOrderId(id);p.setCustomerId(customer);p.setRequestId(input.requestId());p.setExpectedOrderVersion(input.expectedOrderVersion());p.setTransactionId("mock_"+p.getId());p.setAmountTwd(o.getSubtotalTwd()+o.getShippingTwd());payments.insert(p);return new Started(view(payments.find(p.getId())),false);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public Receipt simulate(String customer,String id,Simulate input){
  gateway.requireEnabled();uuid(id);if(shopping.lockCustomer(customer)==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"顧客登入狀態無效");var p=payments.find(id);if(p==null||!p.getCustomerId().equals(customer))throw missing();
  // This demo gateway creates a notification; amount/transaction/owner are never supplied by the browser.
  var notice=new Notification(input.eventId(),id,p.getTransactionId(),p.getAmountTwd(),p.getCurrency(),input.outcome());String raw=gateway.raw(notice),ts=Long.toString(Instant.now().getEpochSecond());return process(gateway.verify(ts,gateway.sign(ts,raw),raw));
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public Receipt receive(String timestamp,String signature,String raw){return process(gateway.verify(timestamp,signature,raw));}
 private Receipt receipt(MockPaymentEvent event,MockPayment p,boolean replay){return new Receipt(event.getEventId(),event.getDisposition(),replay,view(payments.find(p.getId())),orderService.detail(p.getCustomerId(),p.getOrderId()));}
 private Receipt process(Notification input){
  var known=payments.find(input.paymentId());if(known==null)throw missing();
  // Same owner -> order -> payment -> sorted product/inventory lock order as cancellation and expiry.
  if(orders.lockOwner(known.getCustomerId())==null)throw missing();var o=orders.lock(known.getCustomerId(),known.getOrderId());var p=payments.lock(known.getId());if(o==null||p==null)throw missing();
  if(!p.getTransactionId().equals(input.transactionId())||p.getAmountTwd()!=input.amountTwd()||!p.getCurrency().equals(input.currency()))throw conflict("通知交易或金額與付款快照不符");
  String hash=gateway.fingerprint(input);var old=payments.event(input.eventId());if(old!=null){if(!old.getPaymentId().equals(p.getId())||!old.getFingerprint().equals(hash))throw conflict("通知編號已用於不同內容");return receipt(old,p,true);}
  String disposition;
  if("SUCCEEDED".equals(p.getStatus())){disposition="ALREADY_SUCCEEDED";}
  else if("REVIEW_REQUIRED".equals(p.getStatus())){disposition="REVIEW_REQUIRED";}
  else if("FAILURE".equals(input.outcome())){
   if("FAILED".equals(p.getStatus()))disposition="ALREADY_FAILED";else{disposition="PAYMENT_FAILED";payments.state(p.getId(),"FAILED");}
  }else if("FAILED".equals(p.getStatus())){payments.state(p.getId(),"REVIEW_REQUIRED");disposition="REVIEW_REQUIRED";}
  else {
   orderService.expire(p.getCustomerId(),p.getOrderId());o=orders.find(p.getCustomerId(),p.getOrderId());
   if(!"PENDING_PAYMENT".equals(o.getStatus())||payments.unresolvedReviews(o.getId())>0){payments.state(p.getId(),"REVIEW_REQUIRED");disposition="REVIEW_REQUIRED";}
   else {
    var lines=orders.lines(o.getId());for(String product:lines.stream().map(CustomerOrderLine::getProductId).distinct().sorted().toList()){catalog.lock(product);catalog.lockSkus(product);}
    for(var l:lines){if(payments.consume(l.getSkuId(),l.getQuantity())!=1)throw new IllegalStateException("付款保留庫存不一致");orders.event(o.getId(),l.getSkuId(),"CONSUME",l.getQuantity());}
    if(payments.paid(o.getId())!=1)throw new IllegalStateException("付款訂單狀態競爭");payments.state(p.getId(),"SUCCEEDED");disposition="APPLIED";
   }
  }
  var event=new MockPaymentEvent();event.setEventId(input.eventId());event.setPaymentId(p.getId());event.setFingerprint(hash);event.setOutcome(input.outcome());event.setDisposition(disposition);
  try{payments.append(event);}catch(DuplicateKeyException e){throw conflict("通知編號已被使用，請重新核對");}
  if("APPLIED".equals(disposition)||"PAYMENT_FAILED".equals(disposition)||("REVIEW_REQUIRED".equals(disposition)&&!"REVIEW_REQUIRED".equals(p.getStatus()))){
   String kind="APPLIED".equals(disposition)?"PAYMENT_SUCCEEDED":"PAYMENT_FAILED".equals(disposition)?"PAYMENT_FAILED":"PAYMENT_REVIEW_REQUIRED";
   notifications.enqueue(orders.find(p.getCustomerId(),p.getOrderId()),kind,p.getId());
  }
  return receipt(event,p,false);
 }
}
