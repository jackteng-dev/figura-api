package dev.jackteng.figura.service;
import dev.jackteng.figura.dto.FulfillmentPayloads.*;
import dev.jackteng.figura.dto.CatalogPayloads.Page;
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
public class FulfillmentService {
 private final FulfillmentMapper fulfillment;private final OrderMapper orders;private final PaymentMapper payments;private final OrderService orderService;private final ReturnMapper returns;private final boolean enabled;
 private final NotificationCapture notifications;
 public FulfillmentService(FulfillmentMapper fulfillment,OrderMapper orders,PaymentMapper payments,OrderService orderService,ReturnMapper returns,NotificationCapture notifications,@Value("${shop.fulfillment.mock-enabled:false}") boolean enabled){this.notifications=notifications;this.fulfillment=fulfillment;this.orders=orders;this.payments=payments;this.orderService=orderService;this.enabled=enabled;this.returns=returns;}
 private ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
 private void uuid(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"訂單編號不正確");}}
 private CustomerOrder require(String id){uuid(id);var o=fulfillment.find(id);if(o==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"找不到訂單");return o;}
 private boolean ready(CustomerOrder o){return enabled&&"PAID".equals(o.getStatus())&&payments.unresolvedReviews(o.getId())==0&&returns.active(o.getId())==0;}
 private Detail view(CustomerOrder o){boolean ready=ready(o);return new Detail(orderService.detail(o.getCustomerId(),o.getId()),enabled,ready&&"UNFULFILLED".equals(o.getFulfillmentStatus()),ready&&"SHIPPED".equals(o.getFulfillmentStatus()));}
 private String filter(String value,Set<String> allowed){if(value==null||value.isEmpty())return null;if(!allowed.contains(value))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"篩選狀態不正確");return value;}
 @Transactional(readOnly=true) public Page<Summary> list(int page,int pageSize,String status,String fulfillmentStatus){
  if(page<1||page>100000||pageSize<1||pageSize>20)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"分頁範圍不正確");
  status=filter(status,Set.of("PENDING_PAYMENT","PAID","REFUNDED","CANCELLED","EXPIRED"));fulfillmentStatus=filter(fulfillmentStatus,Set.of("UNFULFILLED","SHIPPED","DELIVERED"));
  var items=fulfillment.list(status,fulfillmentStatus,pageSize,(page-1)*pageSize).stream().map(o->new Summary(o.getId(),o.getStatus(),o.getVersion(),o.getSubtotalTwd()+o.getShippingTwd(),o.getFulfillmentStatus(),o.getCreatedAt(),o.getPaidAt())).toList();return new Page<>(items,page,pageSize,fulfillment.count(status,fulfillmentStatus));
 }
 @Transactional(readOnly=true) public Detail detail(String id){return view(require(id));}
 @Transactional(readOnly=true) public List<Audit> history(String id){require(id);return fulfillment.history(id).stream().map(Audit::from).toList();}
 @Transactional(isolation=Isolation.READ_COMMITTED) public Applied change(String id,String action,Change input,CustomUserDetails actor){
  if(!enabled)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"模擬配送未啟用");uuid(id);
  if(!Set.of("SHIP","DELIVER").contains(action))throw new IllegalArgumentException("Unknown fulfillment action");
  String username=fulfillment.lockActor(actor.getId());if(username==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"員工登入狀態無效");
  if(fulfillment.lockPermission(actor.getId(),action.equals("SHIP")?"orders.ship.mock":"orders.deliver.mock").isEmpty())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"無履約操作權限");
  var known=require(id);if(orders.lockOwner(known.getCustomerId())==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"找不到訂單");var o=orders.lock(known.getCustomerId(),id);if(o==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"找不到訂單");
  String note=input.note().trim();var old=fulfillment.request(input.requestId());
  if(old!=null){if(old.getActorId()!=actor.getId()||!old.getOrderId().equals(id)||!old.getAction().equals(action)||old.getBeforeVersion()!=input.expectedOrderVersion()||!old.getNote().equals(note))throw conflict("操作編號已用於不同內容，請重新核對");return new Applied(Audit.from(old),view(o),true);}
  if(o.getVersion()!=input.expectedOrderVersion())throw conflict("訂單已變更，請重新核對");
  if(!ready(o))throw conflict("只有已模擬付款且無待核對通知的訂單可履約");
  String before=action.equals("SHIP")?"UNFULFILLED":"SHIPPED",after=action.equals("SHIP")?"SHIPPED":"DELIVERED";
  if(!before.equals(o.getFulfillmentStatus()))throw conflict("配送狀態已變更，請重新核對");
  String tracking=action.equals("SHIP")?"MOCK-"+UUID.randomUUID():o.getShipmentTrackingId();
  int changed=action.equals("SHIP")?fulfillment.ship(id,tracking,o.getVersion()):fulfillment.deliver(id,o.getVersion());if(changed!=1)throw conflict("配送状态競爭，請重新核對");
  var event=new FulfillmentEvent();event.setRequestId(input.requestId());event.setOrderId(id);event.setActorId(actor.getId());event.setActorUsername(username);event.setAction(action);event.setNote(note);event.setBeforeVersion(o.getVersion());event.setAfterVersion(o.getVersion()+1);event.setBeforeStatus(before);event.setAfterStatus(after);event.setTrackingId(tracking);
  try{fulfillment.append(event);}catch(DuplicateKeyException e){throw conflict("操作編號已被使用，請重新核對");}
  notifications.enqueue(require(id),action.equals("SHIP")?"ORDER_SHIPPED":"ORDER_DELIVERED",id);
  return new Applied(Audit.from(fulfillment.request(input.requestId())),view(require(id)),false);
 }
}
