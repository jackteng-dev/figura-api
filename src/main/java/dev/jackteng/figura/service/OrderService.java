package dev.jackteng.figura.service;
import dev.jackteng.figura.dto.OrderPayloads.*;
import dev.jackteng.figura.dto.CatalogPayloads.Page;
import dev.jackteng.figura.mapper.*;
import dev.jackteng.figura.pojo.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
@Service
public class OrderService {
 private final OrderMapper orders;
 private final ShoppingMapper shopping;
 private final CatalogMapper catalog;
 private final RefundMapper refunds;
 private final ReturnMapper returns;
 private final NotificationCapture notifications;
 public OrderService(OrderMapper orders,ShoppingMapper shopping,CatalogMapper catalog,RefundMapper refunds,ReturnMapper returns,NotificationCapture notifications){this.notifications=notifications;this.orders=orders;this.shopping=shopping;this.catalog=catalog;this.refunds=refunds;this.returns=returns;}
 private ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
 private CustomerOrder require(CustomerOrder o){if(o==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"找不到訂單");return o;}
 private void uuid(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"訂單編號不正確");}}
 private View view(CustomerOrder o){var refund=refunds.orderRefund(o.getId());return new View(o.getId(),o.getStatus(),o.getVersion(),o.getSubtotalTwd(),o.getShippingTwd(),o.getSubtotalTwd()+o.getShippingTwd(),new Shipping(o.getRecipientName(),o.getPhone(),o.getPostalCode(),o.getCity(),o.getDistrict(),o.getStreet()),orders.lines(o.getId()).stream().map(l->new Line(l.getSkuId(),l.getProductId(),l.getTitle(),l.getLabel(),l.getQuantity(),l.getUnitPriceTwd(),(long)l.getQuantity()*l.getUnitPriceTwd())).toList(),o.getCreatedAt(),o.getExpiresAt(),o.getClosedAt(),o.getPaidAt(),new dev.jackteng.figura.dto.FulfillmentPayloads.Progress(o.getFulfillmentStatus(),"MOCK",o.getShipmentTrackingId(),o.getShippedAt(),o.getDeliveredAt()),refund==null?null:new dev.jackteng.figura.dto.RefundPayloads.Progress("REFUNDED","MOCK",refund.getAmountTwd(),refund.getCreatedAt()),dev.jackteng.figura.dto.ReturnPayloads.Progress.from(returns.find(o.getId())));}
 @Transactional(readOnly=true) public View detail(String customer,String id){uuid(id);return view(require(orders.find(customer,id)));}
 @Transactional(readOnly=true) public Page<View> list(String customer,int page,int pageSize){if(page<1||page>100000||pageSize<1||pageSize>20)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"分頁範圍不正確");return new Page<>(orders.list(customer,pageSize,(page-1)*pageSize).stream().map(this::view).toList(),page,pageSize,orders.count(customer));}
 @Transactional(isolation=Isolation.READ_COMMITTED) public Applied place(String customer,Place input){
  if(shopping.lockCustomer(customer)==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"顧客登入狀態無效");
  var prior=orders.request(customer,input.requestId());
  if(prior!=null){
   if(prior.getCartVersion()!=input.cartVersion()||!prior.getAddressId().equals(input.addressId())||prior.getAddressVersion()!=input.addressVersion()||prior.getSubtotalTwd()!=input.expectedSubtotalTwd())throw conflict("操作編號已用於不同內容，請核對訂單");
   // Return current state; a cancelled/expired replay never creates a new reservation.
   expireLocked(prior);return new Applied(view(require(orders.find(customer,prior.getId()))),true);
  }
  var version=shopping.cartVersion(customer);if(version==null||version.intValue()!=input.cartVersion().intValue())throw conflict("購物車已變更，請重新核對");
  var address=shopping.address(input.addressId());if(address==null||address.isDeleted()||!address.getCustomerId().equals(customer))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"找不到本人收件地址");
  if(address.getVersion()!=input.addressVersion())throw conflict("地址已變更，請重新核對");
  var cart=shopping.items(customer);if(cart.isEmpty())throw conflict("購物車是空的");
  if(orders.pending(customer)>=20)throw conflict("待付款訂單已達 20 筆，請先取消或等待到期");
  // Global product ordering shared by reserve/release; every catalog/inventory writer locks product first.
  var products=new TreeMap<String,CatalogProduct>();var skus=new HashMap<String,CatalogSku>();
  for(String id:cart.stream().map(CartItem::getProductId).distinct().sorted().toList()){
   var product=catalog.lock(id);if(product==null||!"PUBLISHED".equals(product.getStatus()))throw conflict("商品已下架，請重新核對購物車");products.put(id,product);
   catalog.lockSkus(id).forEach(s->skus.put(s.getId(),s));
  }
  long subtotal=0;var lines=new ArrayList<CustomerOrderLine>();String orderId=UUID.randomUUID().toString();
  for(var item:cart){
   var sku=skus.get(item.getSkuId());if(sku==null||!sku.isEnabled()||sku.getQuantity()<item.getQuantity())throw conflict("款式已停用或可用庫存不足，請重新核對");
   var l=new CustomerOrderLine();l.setOrderId(orderId);l.setSkuId(sku.getId());l.setProductId(sku.getProductId());l.setTitle(products.get(sku.getProductId()).getTitle());l.setLabel(sku.getLabel());l.setQuantity(item.getQuantity());l.setUnitPriceTwd(sku.getPriceTwd());lines.add(l);subtotal+=(long)l.getQuantity()*l.getUnitPriceTwd();
  }
  if(subtotal!=input.expectedSubtotalTwd())throw conflict("價格已變更，請重新核對金額後再下單");
  var o=new CustomerOrder();o.setId(orderId);o.setCustomerId(customer);o.setRequestId(input.requestId());o.setCartVersion(input.cartVersion());o.setAddressId(address.getId());o.setAddressVersion(address.getVersion());o.setSubtotalTwd(subtotal);o.setRecipientName(address.getRecipientName());o.setPhone(address.getPhone());o.setPostalCode(address.getPostalCode());o.setCity(address.getCity());o.setDistrict(address.getDistrict());o.setStreet(address.getStreet());o.setExpiresAt(orders.now().plusMinutes(30));orders.insert(o);
  for(var l:lines){orders.insertLine(l);if(orders.reserve(l.getSkuId(),l.getQuantity())!=1)throw conflict("可用庫存不足，請重新核對");orders.event(orderId,l.getSkuId(),"RESERVE",l.getQuantity());}
  orders.clearCart(customer);shopping.bumpCart(customer);notifications.enqueue(require(orders.find(customer,orderId)),"ORDER_PLACED",orderId);return new Applied(view(require(orders.find(customer,orderId))),false);
 }
 private void closeLocked(CustomerOrder o,String state){
  var lines=orders.lines(o.getId());for(String id:lines.stream().map(CustomerOrderLine::getProductId).distinct().sorted().toList()){catalog.lock(id);catalog.lockSkus(id);}
  for(var l:lines){if(orders.release(l.getSkuId(),l.getQuantity())!=1)throw new IllegalStateException("訂單保留庫存不一致");orders.event(o.getId(),l.getSkuId(),"RELEASE",l.getQuantity());}
  if(orders.close(o.getId(),state)!=1)throw new IllegalStateException("訂單狀態競爭");
  notifications.enqueue(require(orders.find(o.getCustomerId(),o.getId())),"ORDER_"+state,o.getId());
 }
 private boolean expireLocked(CustomerOrder o){if("PENDING_PAYMENT".equals(o.getStatus())&&!o.getExpiresAt().isAfter(orders.now())){closeLocked(o,"EXPIRED");return true;}return false;}
 @Transactional(isolation=Isolation.READ_COMMITTED) public View cancel(String customer,String id,Cancel input){
  uuid(id);if(shopping.lockCustomer(customer)==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"顧客登入狀態無效");var o=require(orders.lock(customer,id));
  if("PAID".equals(o.getStatus()))throw conflict("已模擬付款的訂單需走退款流程，不可直接取消");
  // Terminal replay is harmless and returns the current state, regardless of stale version.
  if(!"PENDING_PAYMENT".equals(o.getStatus()))return view(o);
  if(expireLocked(o))return view(require(orders.find(customer,id)));
  if(o.getVersion()!=input.expectedVersion())throw conflict("訂單已變更，請重新核對");
  closeLocked(o,"CANCELLED");return view(require(orders.find(customer,id)));
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public void expire(String customer,String id){
  if(orders.lockOwner(customer)==null)return;var o=orders.lock(customer,id);if(o!=null)expireLocked(o);
 }
}
