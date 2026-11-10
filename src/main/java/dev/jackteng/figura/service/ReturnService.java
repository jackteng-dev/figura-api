package dev.jackteng.figura.service;
import dev.jackteng.figura.dto.ReturnPayloads.*;
import dev.jackteng.figura.dto.FulfillmentPayloads.Change;
import dev.jackteng.figura.mapper.*;
import dev.jackteng.figura.pojo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
@Service
public class ReturnService {
 private final ReturnMapper returns;private final OrderMapper orders;private final ShoppingMapper customers;private final FulfillmentMapper staff;private final PaymentMapper payments;private final RefundMapper refunds;private final OrderService orderService;private final boolean enabled,refundEnabled;
 private final NotificationCapture notifications;
 public ReturnService(ReturnMapper returns,OrderMapper orders,ShoppingMapper customers,FulfillmentMapper staff,PaymentMapper payments,RefundMapper refunds,OrderService orderService,NotificationCapture notifications,@Value("${shop.returns.mock-enabled:false}") boolean enabled,@Value("${shop.refunds.mock-enabled:false}") boolean refundEnabled){this.notifications=notifications;this.returns=returns;this.orders=orders;this.customers=customers;this.staff=staff;this.payments=payments;this.refunds=refunds;this.orderService=orderService;this.enabled=enabled;this.refundEnabled=refundEnabled;}
 private ResponseStatusException conflict(String m){return new ResponseStatusException(HttpStatus.CONFLICT,m);}
 private ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"找不到本人訂單或退貨申請");}
 private void uuid(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"訂單編號不正確");}}
 private CustomerOrder require(String id){uuid(id);var o=staff.find(id);if(o==null)throw missing();return o;}
 private boolean shipped(CustomerOrder o){return "PAID".equals(o.getStatus())&&Set.of("SHIPPED","DELIVERED").contains(o.getFulfillmentStatus());}
 private boolean canRequest(CustomerOrder o){return enabled&&shipped(o)&&payments.unresolvedReviews(o.getId())==0&&returns.find(o.getId())==null;}
 private Detail view(CustomerOrder o){var r=returns.find(o.getId());boolean requested=enabled&&shipped(o)&&r!=null&&"REQUESTED".equals(r.getStatus());boolean refundable=enabled&&refundEnabled&&shipped(o)&&r!=null&&"RECEIVED".equals(r.getStatus())&&payments.unresolvedReviews(o.getId())==0;return new Detail(enabled,requested,requested,refundable,Progress.from(r),returns.history(o.getId()).stream().map(Audit::from).toList());}
 @Transactional(readOnly=true) public Detail detail(String id){return view(require(id));}
 @Transactional(readOnly=true) public Options options(String customer,String id){uuid(id);var o=orders.find(customer,id);if(o==null)throw missing();return new Options(enabled,canRequest(o),orderService.detail(customer,id));}
 @Transactional(isolation=Isolation.READ_COMMITTED) public Requested request(String customer,String id,Request input){
  if(!enabled)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"模擬退貨未啟用");uuid(id);if(customers.lockCustomer(customer)==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"顧客登入狀態無效");var o=orders.lock(customer,id);if(o==null)throw missing();String reason=input.reason().trim();var old=returns.request(input.requestId());
  if(old!=null){if(!old.getCustomerId().equals(customer)||!old.getOrderId().equals(id)||old.getExpectedOrderVersion()!=input.expectedOrderVersion()||!old.getReason().equals(reason))throw conflict("申請編號已用於不同內容");return new Requested(orderService.detail(customer,id),true);}
  if(o.getVersion()!=input.expectedOrderVersion())throw conflict("訂單已變更，請重新核對");if(!canRequest(o))throw conflict("僅已出貨或送達、無待核對付款且尚未申請的訂單可整筆退貨");
  var r=new OrderReturn();r.setOrderId(id);r.setRequestId(input.requestId());r.setCustomerId(customer);r.setExpectedOrderVersion(o.getVersion());r.setReason(reason);try{returns.insert(r);}catch(DuplicateKeyException e){throw conflict("申請編號或訂單已被使用，請重新核對");}if(refunds.reviewed(id,o.getVersion())!=1)throw conflict("訂單已變更");notifications.enqueue(require(id),"RETURN_REQUESTED",id);return new Requested(orderService.detail(customer,id),false);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public Applied change(String id,String action,Change input,String disposition,CustomUserDetails actor){
  if(!enabled)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"模擬退貨未啟用");uuid(id);if(!Set.of("RECEIVE","REJECT").contains(action))throw new IllegalArgumentException("Unknown return action");
  if(action.equals("RECEIVE")&&(disposition==null||!Set.of("RESTOCK","NO_RESTOCK").contains(disposition)))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"驗收處置不正確");if(action.equals("REJECT")&&disposition!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"拒絕申請不可指定庫存處置");
  String username=staff.lockActor(actor.getId());if(username==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"員工登入狀態無效");if(staff.lockPermission(actor.getId(),action.equals("RECEIVE")?"orders.return.receive.mock":"orders.return.reject.mock").isEmpty())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"無退貨處理權限");
  var known=require(id);if(orders.lockOwner(known.getCustomerId())==null)throw missing();var o=orders.lock(known.getCustomerId(),id);var r=returns.find(id);if(r==null)throw missing();String note=input.note().trim();var old=returns.event(input.requestId());
  if(old!=null){if(old.getActorId()!=actor.getId()||!old.getOrderId().equals(id)||!old.getAction().equals(action)||old.getBeforeVersion()!=input.expectedOrderVersion()||!old.getNote().equals(note)||!Objects.equals(old.getDisposition(),disposition))throw conflict("操作編號已用於不同內容");return new Applied(Audit.from(old),orderService.detail(o.getCustomerId(),id),view(o),true);}
  if(o.getVersion()!=input.expectedOrderVersion())throw conflict("訂單已變更，請重新核對");if(!shipped(o)||!"REQUESTED".equals(r.getStatus()))throw conflict("只可處理待驗收的退貨申請");
  int changed=action.equals("RECEIVE")?returns.receive(id,disposition):returns.reject(id);if(changed!=1||refunds.reviewed(id,o.getVersion())!=1)throw conflict("退貨狀態已變更");var event=new ReturnEvent();event.setRequestId(input.requestId());event.setOrderId(id);event.setActorId(actor.getId());event.setActorUsername(username);event.setAction(action);event.setNote(note);event.setBeforeVersion(o.getVersion());event.setAfterVersion(o.getVersion()+1);event.setBeforeStatus("REQUESTED");event.setAfterStatus(action.equals("RECEIVE")?"RECEIVED":"REJECTED");event.setDisposition(disposition);try{returns.append(event);}catch(DuplicateKeyException e){throw conflict("操作編號已被使用，請重新核對");}notifications.enqueue(require(id),action.equals("RECEIVE")?"RETURN_RECEIVED":"RETURN_REJECTED",id);return new Applied(Audit.from(returns.event(input.requestId())),orderService.detail(o.getCustomerId(),id),view(require(id)),false);
 }
}
