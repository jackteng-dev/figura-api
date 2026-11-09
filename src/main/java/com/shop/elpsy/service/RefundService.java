package com.shop.elpsy.service;
import com.shop.elpsy.dto.RefundPayloads.*;
import com.shop.elpsy.dto.FulfillmentPayloads.Change;
import com.shop.elpsy.mapper.*;
import com.shop.elpsy.pojo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
@Service
public class RefundService {
 private final RefundMapper refunds;private final FulfillmentMapper staff;private final OrderMapper orders;private final PaymentMapper payments;private final CatalogMapper catalog;private final OrderService orderService;private final ReturnMapper returns;private final boolean enabled,returnsEnabled;
 private final NotificationCapture notifications;
 public RefundService(RefundMapper refunds,FulfillmentMapper staff,OrderMapper orders,PaymentMapper payments,CatalogMapper catalog,OrderService orderService,ReturnMapper returns,NotificationCapture notifications,@Value("${shop.refunds.mock-enabled:false}") boolean enabled,@Value("${shop.returns.mock-enabled:false}") boolean returnsEnabled){this.notifications=notifications;this.refunds=refunds;this.staff=staff;this.orders=orders;this.payments=payments;this.catalog=catalog;this.orderService=orderService;this.enabled=enabled;this.returns=returns;this.returnsEnabled=returnsEnabled;}
 private ResponseStatusException conflict(String m){return new ResponseStatusException(HttpStatus.CONFLICT,m);}
 private void uuid(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"操作編號不正確");}}
 private CustomerOrder require(String id){uuid(id);var o=staff.find(id);if(o==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"找不到訂單");return o;}
 private boolean eligible(CustomerOrder o){return enabled&&"PAID".equals(o.getStatus())&&"UNFULFILLED".equals(o.getFulfillmentStatus())&&payments.unresolvedReviews(o.getId())==0;}
 private Detail view(CustomerOrder o){return new Detail(enabled,eligible(o),payments.list(o.getId()).stream().filter(p->"REVIEW_REQUIRED".equals(p.getStatus())).map(p->new Review(p.getId(),p.getAmountTwd(),p.getCurrency(),p.getSettledAt(),refunds.payment(p.getId())!=null,payments.history(p.getId()).stream().map(e->new Evidence(e.getEventId(),e.getOutcome(),e.getDisposition(),e.getCreatedAt())).toList())).toList(),refunds.history(o.getId()).stream().map(Audit::from).toList());}
 @Transactional(readOnly=true) public Detail detail(String id){return view(require(id));}
 @Transactional(isolation=Isolation.READ_COMMITTED) public Applied change(String id,String reviewPayment,Change input,CustomUserDetails actor){return apply(id,reviewPayment,input,actor,false);}
 @Transactional(isolation=Isolation.READ_COMMITTED) public Applied changeReturned(String id,Change input,CustomUserDetails actor){return apply(id,null,input,actor,true);}
 private Applied apply(String id,String reviewPayment,Change input,CustomUserDetails actor,boolean returned){
  if(!enabled||(returned&&!returnsEnabled))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"模擬退款未啟用");uuid(id);if(reviewPayment!=null)uuid(reviewPayment);
  String action=returned?"RETURN_REFUND":reviewPayment==null?"ORDER_REFUND":"REVIEW_REFUND";
  String username=staff.lockActor(actor.getId());if(username==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"員工登入狀態無效");
  if(staff.lockPermission(actor.getId(),returned?"orders.return.refund.mock":reviewPayment==null?"orders.refund.mock":"orders.review.mock").isEmpty())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"無退款核對操作權限");
  var known=require(id);if(orders.lockOwner(known.getCustomerId())==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"找不到訂單");var o=orders.lock(known.getCustomerId(),id);
  String note=input.note().trim();var old=refunds.request(input.requestId());
  if(old!=null){if(old.getActorId()!=actor.getId()||!old.getOrderId().equals(id)||!old.getAction().equals(action)||old.getBeforeVersion()!=input.expectedOrderVersion()||!old.getNote().equals(note)||(reviewPayment!=null&&!old.getPaymentId().equals(reviewPayment)))throw conflict("操作編號已用於不同內容，請重新核對");return new Applied(Audit.from(old),orderService.detail(o.getCustomerId(),id),view(o),true);}
  if(o.getVersion()!=input.expectedOrderVersion())throw conflict("訂單已變更，請重新核對");
  var returnRequest=returned?returns.find(id):null;
  if(returned&&(returnRequest==null||!"RECEIVED".equals(returnRequest.getStatus())||!"PAID".equals(o.getStatus())||!Set.of("SHIPPED","DELIVERED").contains(o.getFulfillmentStatus())||payments.unresolvedReviews(id)>0))throw conflict("只有已驗收且無待核對付款的退貨可退款");
  if(returned&&returns.event(input.requestId())!=null)throw conflict("退貨操作編號已被使用");
  MockPayment p;
  if(reviewPayment==null){
   if(!returned&&!eligible(o))throw conflict("只可退款已付款、未出貨且無待核對付款的訂單");
   var successful=payments.list(id).stream().filter(a->"SUCCEEDED".equals(a.getStatus())).toList();if(successful.size()!=1)throw conflict("付款紀錄需先核對");p=payments.lock(successful.get(0).getId());
  }else{
   p=payments.lock(reviewPayment);if(p==null||!id.equals(p.getOrderId()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"找不到此訂單的待核對付款");
   if(!"REVIEW_REQUIRED".equals(p.getStatus())||refunds.payment(p.getId())!=null)throw conflict("付款沒有未處理的核對狀態");
   // The original signed history is immutable; only mock unresolved SUCCESS evidence can be refunded.
   if(payments.reviewSuccess(p.getId())==0)throw conflict("缺少待核對成功通知");
  }
  if(p.getAmountTwd()!=o.getSubtotalTwd()+o.getShippingTwd()||!"TWD".equals(p.getCurrency()))throw conflict("付款快照不一致");
  if(reviewPayment==null){
   if(!returned||"RESTOCK".equals(returnRequest.getDisposition())){
   var lines=orders.lines(id);for(String product:lines.stream().map(CustomerOrderLine::getProductId).distinct().sorted().toList()){catalog.lock(product);catalog.lockSkus(product);}
   for(var l:lines){if(refunds.restock(l.getSkuId(),l.getQuantity())!=1)throw conflict("回補後庫存超過上限，請先核對庫存");orders.event(id,l.getSkuId(),"RETURN",l.getQuantity());}
   }
   if((returned?refunds.refundReturn(id,o.getVersion()):refunds.refund(id,o.getVersion()))!=1)throw conflict("退款狀態已變更");
  }else if(refunds.reviewed(id,o.getVersion())!=1)throw conflict("核對狀態已變更");
  var event=new RefundEvent();event.setRequestId(input.requestId());event.setOrderId(id);event.setPaymentId(p.getId());event.setActorId(actor.getId());event.setActorUsername(username);event.setAction(action);event.setNote(note);event.setAmountTwd(p.getAmountTwd());event.setBeforeVersion(o.getVersion());event.setAfterVersion(o.getVersion()+1);
  try{refunds.append(event);}catch(DuplicateKeyException e){throw conflict("操作編號或付款已被處理，請重新核對");}
  if(returned){
   if(returns.refund(id)!=1)throw conflict("退貨狀態已變更");
   var audit=new ReturnEvent();audit.setRequestId(input.requestId());audit.setOrderId(id);audit.setActorId(actor.getId());audit.setActorUsername(username);audit.setAction("REFUND");audit.setNote(note);audit.setBeforeVersion(o.getVersion());audit.setAfterVersion(o.getVersion()+1);audit.setBeforeStatus("RECEIVED");audit.setAfterStatus("REFUNDED");audit.setDisposition(returnRequest.getDisposition());try{returns.append(audit);}catch(DuplicateKeyException e){throw conflict("退貨操作編號已被使用");}
  }
  var current=require(id);notifications.enqueue(current,reviewPayment==null?"ORDER_REFUNDED":"REVIEW_REFUNDED",reviewPayment==null?id:reviewPayment);return new Applied(Audit.from(refunds.request(input.requestId())),orderService.detail(current.getCustomerId(),id),view(current),false);
 }
}
