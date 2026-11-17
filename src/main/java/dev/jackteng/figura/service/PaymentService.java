package dev.jackteng.figura.service;
import dev.jackteng.figura.dto.PaymentPayloads.*;
import dev.jackteng.figura.mapper.*;
import dev.jackteng.figura.pojo.*;
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
 private final NotificationCapture notifications;private final EcpayGateway ecpay;
 public PaymentService(PaymentMapper payments,OrderMapper orders,ShoppingMapper shopping,CatalogMapper catalog,OrderService orderService,MockPaymentGateway gateway,NotificationCapture notifications,EcpayGateway ecpay){this.ecpay=ecpay;this.notifications=notifications;this.payments=payments;this.orders=orders;this.shopping=shopping;this.catalog=catalog;this.orderService=orderService;this.gateway=gateway;}
 private ResponseStatusException conflict(String m){return new ResponseStatusException(HttpStatus.CONFLICT,m);}
 private ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"找不到付款或本人訂單");}
 private void uuid(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"付款編號不正確");}}
 private View view(MockPayment p){var refund=payments.refund(p.getId());return new View(p.getId(),p.getOrderId(),p.getStatus(),p.getAmountTwd(),p.getCurrency(),p.getProvider()==null?"MOCK":p.getProvider(),p.getCreatedAt(),p.getSettledAt(),refund==null?null:new dev.jackteng.figura.dto.RefundPayloads.Progress("REFUNDED","MOCK",refund.getAmountTwd(),refund.getCreatedAt()));}
 private boolean canStart(CustomerOrder o,List<MockPayment> all){return "PENDING_PAYMENT".equals(o.getStatus())&&o.getExpiresAt().isAfter(orders.now())&&all.size()<20&&all.stream().allMatch(p->"FAILED".equals(p.getStatus())||("REVIEW_REQUIRED".equals(p.getStatus())&&payments.reviewResolved(p.getId())==1));}
 @Transactional(readOnly=true) public Options options(String customer,String id){uuid(id);var o=orders.find(customer,id);if(o==null)throw missing();if(!gateway.enabled()&&!ecpay.enabled())return new Options(false,false,List.of(),false);var all=payments.list(id);return new Options(gateway.enabled(),gateway.enabled()&&canStart(o,all),all.stream().map(this::view).toList(),ecpay.enabled()&&canStartEcpay(o,all));}
 @Transactional(isolation=Isolation.READ_COMMITTED) public Started start(String customer,String id,Start input){
  gateway.requireEnabled();uuid(id);if(shopping.lockCustomer(customer)==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"顧客登入狀態無效");var o=orders.lock(customer,id);if(o==null)throw missing();
  var prior=payments.request(customer,input.requestId());if(prior!=null){if(!prior.getOrderId().equals(id)||!"MOCK".equals(prior.getProvider())||prior.getExpectedOrderVersion()!=input.expectedOrderVersion())throw conflict("付款操作編號已用於不同內容");return new Started(view(prior),true);}
  if(o.getVersion()!=input.expectedOrderVersion())throw conflict("訂單已變更，請重新核對");
  if(!canStart(o,payments.list(id)))throw conflict("目前不可建立新付款，請核對既有付款或訂單狀態");
  var p=new MockPayment();p.setId(UUID.randomUUID().toString());p.setOrderId(id);p.setCustomerId(customer);p.setRequestId(input.requestId());p.setExpectedOrderVersion(input.expectedOrderVersion());p.setTransactionId("mock_"+p.getId());p.setAmountTwd(o.getSubtotalTwd()+o.getShippingTwd());payments.insert(p);return new Started(view(payments.find(p.getId())),false);
 }
 /** 未完成的綠界付款可被同一顧客的新付款取代；之後才到的成功通知會依既有規則轉為待人工核對，不會重複扣庫。 */
 private boolean supersedable(MockPayment p){return "ECPAY".equals(p.getProvider())&&"INITIATED".equals(p.getStatus());}
 private boolean canStartEcpay(CustomerOrder o,List<MockPayment> all){return canStart(o,all.stream().filter(p->!supersedable(p)).toList())&&all.size()<20&&o.getSubtotalTwd()+o.getShippingTwd()>0;}
 private EcpayStarted ecpayStarted(MockPayment p,boolean replayed){
  var checkout="INITIATED".equals(p.getStatus())?new EcpayCheckout(ecpay.checkoutUrl(),ecpay.checkoutFields(p.getTransactionId(),p.getAmountTwd(),p.getOrderId())):null;
  return new EcpayStarted(view(p),replayed,checkout);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public EcpayStarted startEcpay(String customer,String id,Start input){
  ecpay.requireEnabled();uuid(id);if(shopping.lockCustomer(customer)==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"顧客登入狀態無效");var o=orders.lock(customer,id);if(o==null)throw missing();
  var prior=payments.request(customer,input.requestId());if(prior!=null){if(!prior.getOrderId().equals(id)||!"ECPAY".equals(prior.getProvider())||prior.getExpectedOrderVersion()!=input.expectedOrderVersion())throw conflict("付款操作編號已用於不同內容");return ecpayStarted(payments.find(prior.getId()),true);}
  if(o.getVersion()!=input.expectedOrderVersion())throw conflict("訂單已變更，請重新核對");
  var all=payments.list(id);if(!canStartEcpay(o,all))throw conflict("目前不可建立新付款，請核對既有付款或訂單狀態");
  for(var old:all)if(supersedable(old)){
   var locked=payments.lock(old.getId());if(locked==null||!supersedable(locked))throw conflict("付款狀態已變更，請重新核對");
   payments.state(old.getId(),"FAILED");
   var event=new MockPaymentEvent();event.setEventId(UUID.randomUUID().toString());event.setPaymentId(old.getId());event.setFingerprint(sha256("ECPAY_SUPERSEDED|"+old.getId()+"|"+input.requestId()));event.setOutcome("FAILURE");event.setDisposition("PAYMENT_FAILED");payments.append(event);
  }
  var p=new MockPayment();p.setId(UUID.randomUUID().toString());p.setOrderId(id);p.setCustomerId(customer);p.setRequestId(input.requestId());p.setExpectedOrderVersion(input.expectedOrderVersion());p.setTransactionId(ecpay.newTradeNo());p.setProvider("ECPAY");p.setAmountTwd(o.getSubtotalTwd()+o.getShippingTwd());
  try{payments.insert(p);}catch(DuplicateKeyException e){throw conflict("付款編號衝突，請重試同一操作");}
  return ecpayStarted(payments.find(p.getId()),false);
 }
 private static String sha256(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("SHA-256 unavailable");}}
 /** 綠界 ReturnURL 處理結果；applied=false 表示通知已驗證，但依設定忽略綠界後台「模擬付款」。 */
 public record EcpayOutcome(boolean applied,String disposition){}
 @Transactional(isolation=Isolation.READ_COMMITTED) public EcpayOutcome receiveEcpay(Map<String,String> params){
  ecpay.requireEnabled();
  // 簽章計算不分欄位大小寫；讀值也必須不分大小寫，且不可有僅大小寫不同的重複欄位，否則可藉改名讓驗簽與判讀不一致。
  var normalized=new TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER);for(var e:params.entrySet())if(normalized.putIfAbsent(e.getKey(),e.getValue())!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Parameter Error");params=normalized;
  if(!ecpay.verify(params))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"CheckMacValue Error");
  String merchant=params.get("MerchantID"),tradeNo=params.get("MerchantTradeNo"),gatewayNo=params.getOrDefault("TradeNo",""),rtn=params.get("RtnCode"),amount=params.get("TradeAmt"),simulated=params.get("SimulatePaid");
  if(!ecpay.merchantId().equals(merchant))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"MerchantID Error");
  if(tradeNo==null||!tradeNo.matches("[0-9A-Za-z]{1,20}")||rtn==null||!rtn.matches("-?[0-9]{1,8}")||amount==null||!amount.matches("[0-9]{1,12}")||!gatewayNo.matches("[0-9A-Za-z]{0,20}")||simulated==null||!simulated.matches("[01]"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Parameter Error");
  if("1".equals(simulated)&&!ecpay.acceptSimulated())return new EcpayOutcome(false,"IGNORED_SIMULATED");
  // 簽章也不分欄位值大小寫：編號一律轉大寫再查詢與去重（本站 MerchantTradeNo 只產生大寫）。
  tradeNo=tradeNo.toUpperCase(java.util.Locale.ROOT);gatewayNo=gatewayNo.toUpperCase(java.util.Locale.ROOT);
  var known=payments.ecpay(tradeNo);if(known==null)throw missing();
  String outcome="1".equals(rtn)?"SUCCESS":"FAILURE";
  if("SUCCESS".equals(outcome)&&gatewayNo.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Parameter Error");
  String eventId=UUID.nameUUIDFromBytes(("ECPAY|"+tradeNo+"|"+gatewayNo+"|"+rtn).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
  String fingerprint=sha256(String.join("|","ECPAY",merchant,tradeNo,gatewayNo,rtn,amount,simulated,params.getOrDefault("PaymentType","")));
  var receipt=process(new Settle(eventId,known.getId(),tradeNo,Long.parseLong(amount),"TWD",outcome,fingerprint));
  if("SUCCESS".equals(outcome))payments.gatewayTradeNo(known.getId(),gatewayNo);
  return new EcpayOutcome(true,receipt.disposition());
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public Receipt simulate(String customer,String id,Simulate input){
  gateway.requireEnabled();uuid(id);if(shopping.lockCustomer(customer)==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"顧客登入狀態無效");var p=payments.find(id);if(p==null||!p.getCustomerId().equals(customer))throw missing();
  // This demo gateway creates a notification; amount/transaction/owner are never supplied by the browser.
  var notice=new Notification(input.eventId(),id,p.getTransactionId(),p.getAmountTwd(),p.getCurrency(),input.outcome());String raw=gateway.raw(notice),ts=Long.toString(Instant.now().getEpochSecond());return process(settle(gateway.verify(ts,gateway.sign(ts,raw),raw)));
 }
 @Transactional(isolation=Isolation.READ_COMMITTED) public Receipt receive(String timestamp,String signature,String raw){return process(settle(gateway.verify(timestamp,signature,raw)));}
 private Settle settle(Notification n){var p=payments.find(n.paymentId());if(p!=null&&!"MOCK".equals(p.getProvider()))throw conflict("付款通知來源與付款方式不符");return new Settle(n.eventId(),n.paymentId(),n.transactionId(),n.amountTwd(),n.currency(),n.outcome(),gateway.fingerprint(n));}
 /** 已驗證來源的付款結果；金額與交易編號仍需和付款快照一致。 */
 private record Settle(String eventId,String paymentId,String transactionId,long amountTwd,String currency,String outcome,String fingerprint){}
 private Receipt receipt(MockPaymentEvent event,MockPayment p,boolean replay){return new Receipt(event.getEventId(),event.getDisposition(),replay,view(payments.find(p.getId())),orderService.detail(p.getCustomerId(),p.getOrderId()));}
 private Receipt process(Settle input){
  var known=payments.find(input.paymentId());if(known==null)throw missing();
  // Same owner -> order -> payment -> sorted product/inventory lock order as cancellation and expiry.
  if(orders.lockOwner(known.getCustomerId())==null)throw missing();var o=orders.lock(known.getCustomerId(),known.getOrderId());var p=payments.lock(known.getId());if(o==null||p==null)throw missing();
  if(!p.getTransactionId().equals(input.transactionId())||p.getAmountTwd()!=input.amountTwd()||!p.getCurrency().equals(input.currency()))throw conflict("通知交易或金額與付款快照不符");
  String hash=input.fingerprint();var old=payments.event(input.eventId());if(old!=null){if(!old.getPaymentId().equals(p.getId())||!old.getFingerprint().equals(hash))throw conflict("通知編號已用於不同內容");return receipt(old,p,true);}
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
