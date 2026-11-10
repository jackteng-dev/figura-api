package dev.jackteng.figura;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real isolated MySQL/Redis and complete HTTP security chain. No mock owners or prices. */
@SpringBootTest @AutoConfigureMockMvc(print=MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named="SHOP_MYSQL_INTEGRATION",matches="true")
class NotificationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @org.springframework.boot.test.mock.mockito.SpyBean JdbcTemplate jdbc;
    @Autowired dev.jackteng.figura.service.NotificationService notifications;
    @Autowired dev.jackteng.figura.service.NotificationCapture capture;
    @Autowired StringRedisTemplate redis;
    @Autowired dev.jackteng.figura.service.OrderService orderService;
    @Autowired dev.jackteng.figura.service.OrderExpiryJob expiry;
    @Autowired dev.jackteng.figura.service.MockPaymentGateway gateway;
    @org.springframework.boot.test.mock.mockito.SpyBean dev.jackteng.figura.mapper.RefundMapper refundMapper;
    @Autowired dev.jackteng.figura.service.RefundService refundService;
    @Autowired dev.jackteng.figura.service.ReturnService returnService;
    @org.springframework.boot.test.mock.mockito.SpyBean dev.jackteng.figura.mapper.ReturnMapper returnMapper;
    private final Set<Integer> actors=new HashSet<>();
    private final Set<String> customers=ConcurrentHashMap.newKeySet(),products=ConcurrentHashMap.newKeySet(),sessions=ConcurrentHashMap.newKeySet();
    record Client(String csrf,List<Cookie> cookies,String id) {}
    record Fixture(String product,String sku) {String cart(){return "/customer/cart/items/"+sku;}}
    private JsonNode data(MvcResult r) throws Exception{return json.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");}
    private Cookie cookie(MvcResult r,String name){var h=r.getResponse().getHeaders("Set-Cookie").stream().filter(v->v.startsWith(name+"=")).findFirst().orElseThrow();return new Cookie(name,h.split(";",2)[0].substring(name.length()+1));}
    private MvcResult call(Client c,String method,String path,Object body) throws Exception{
        var b=request(org.springframework.http.HttpMethod.valueOf(method),"/figura/api"+path).contextPath("/figura").servletPath("/api"+path.split("\\?",2)[0]);
        if(c!=null){b.cookie(c.cookies().toArray(Cookie[]::new));if(!method.equals("GET"))b.header("X-XSRF-TOKEN",c.csrf());}
        if(body!=null)b.contentType("application/json").content(json.writeValueAsBytes(body));return mvc.perform(b).andReturn();
    }
    private Client guest() throws Exception{var r=call(null,"GET","/auth/csrf",null);return new Client(data(r).path("token").asText(),List.of(cookie(r,"XSRF-TOKEN")),"");}
    private Client customer() throws Exception{
        var c=guest();String name="cart_"+UUID.randomUUID().toString().replace("-","").substring(0,20),password="test-cart-password-2026",device=UUID.randomUUID().toString();
        var r=call(c,"POST","/customer/auth/register",Map.of("username",name,"password",password,"displayName","合成顧客"));assertEquals(201,r.getResponse().getStatus());String id=data(r).path("id").asText();customers.add(id);
        r=call(c,"POST","/customer/auth/login",Map.of("username",name,"password",password,"deviceId",device,"rememberMe",false));assertEquals(200,r.getResponse().getStatus());sessions.add("shop:customer:session:"+id+":"+device);
        return new Client(c.csrf(),List.of(c.cookies().get(0),cookie(r,"customerAccessToken"),cookie(r,"customerRefreshToken")),id);
    }
    private Client admin() throws Exception{
        var c=guest();String device=UUID.randomUUID().toString();var r=mvc.perform(post("/figura/api/admin/login").contextPath("/figura").servletPath("/api/admin/login").cookie(c.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN",c.csrf()).param("username","shop_admin").param("password",System.getenv("DEMO_ADMIN_PASSWORD")).param("deviceId",device)).andReturn();assertEquals(200,r.getResponse().getStatus());String id=data(r).path("id").asText();sessions.add("shop:session:"+id+":"+device);
        return new Client(c.csrf(),List.of(c.cookies().get(0),cookie(r,"accessToken"),cookie(r,"refreshToken")),id);
    }
    private Fixture fixture(Client admin,int stock) throws Exception{
        var r=call(admin,"POST","/admin/products",Map.of("title","Shopping "+UUID.randomUUID(),"brand","Figura","category","公仔","description","合成商品","expectedVersion",0,"skus",List.of(Map.of("code","CART-"+UUID.randomUUID(),"label","標準款","priceTwd",1280,"enabled",true))));assertEquals(201,r.getResponse().getStatus());var p=data(r);var f=new Fixture(p.path("id").asText(),p.path("skus").get(0).path("id").asText());products.add(f.product());
        if(stock>0){r=call(admin,"POST","/admin/products/"+f.product()+"/skus/"+f.sku()+"/inventory/adjustments",Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",0,"delta",stock,"reason","RESTOCK","note","合成購物車測試入庫"));assertEquals(201,r.getResponse().getStatus());}
        assertEquals(200,call(admin,"PATCH","/admin/products/"+f.product()+"/publication",Map.of("expectedVersion",0,"published",true)).getResponse().getStatus());return f;
    }
    private JsonNode cart(Client c) throws Exception{var r=call(c,"GET","/customer/cart",null);assertEquals(200,r.getResponse().getStatus());return data(r);}
    private Map<String,Object> quantity(int version,int quantity){return new HashMap<>(Map.of("expectedVersion",version,"quantity",quantity));}
    private Map<String,Object> address(int version){return new HashMap<>(Map.of("expectedVersion",version,"recipientName","合成收件人","phone","0912345678","postalCode","100","city","測試市","district","測試區","street","合成路 1 號"));}
    @AfterEach void cleanup(){
        org.mockito.Mockito.reset(refundMapper);org.mockito.Mockito.reset(returnMapper);org.mockito.Mockito.reset(jdbc);
        for(int id:actors)jdbc.update("UPDATE user SET is_stop='T' WHERE id=?",id);
        // Keep immutable synthetic order/audit history; release only this test's pending orders.
        for(String customer:customers){
            for(String order:jdbc.queryForList("SELECT id FROM customer_order WHERE customer_id=? AND status='PENDING_PAYMENT'",String.class,customer)){
                jdbc.update("UPDATE customer_order SET expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",order);
                orderService.expire(customer,order);
            }
            jdbc.update("UPDATE customer SET enabled=FALSE WHERE id=?",customer);
        }
        for(String id:products)jdbc.update("UPDATE catalog_product SET status='ARCHIVED' WHERE id=?",id);
        if(!sessions.isEmpty())redis.delete(sessions);
    }
    private Map<String,Object> ready(Client c,Fixture f,int qty) throws Exception{
        assertEquals(200,call(c,"PUT",f.cart(),quantity(cart(c).path("version").asInt(),qty)).getResponse().getStatus());
        String address=UUID.randomUUID().toString();assertEquals(200,call(c,"PUT","/customer/addresses/"+address,address(0)).getResponse().getStatus());
        var v=cart(c);return new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"cartVersion",v.path("version").asInt(),"addressId",address,"addressVersion",1,"expectedSubtotalTwd",v.path("subtotalTwd").asLong()));
    }
    private JsonNode place(Client c,Map<String,Object> body) throws Exception{var r=call(c,"POST","/customer/orders",body);assertEquals(200,r.getResponse().getStatus(),r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));return data(r).path("order");}
    private int reserved(Fixture f){return jdbc.queryForObject("SELECT reserved FROM sku_inventory WHERE sku_id=?",Integer.class,f.sku());}
    private void due(JsonNode order){jdbc.update("UPDATE customer_order SET expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",order.path("id").asText());}
    private MvcResult cancel(Client c,JsonNode o) throws Exception{return call(c,"POST","/customer/orders/"+o.path("id").asText()+"/cancel",Map.of("expectedVersion",o.path("version").asInt()));}
    private JsonNode intent(Client c,JsonNode order) throws Exception{
        var r=call(c,"POST","/customer/orders/"+order.path("id").asText()+"/mock-payments",Map.of("requestId",UUID.randomUUID().toString(),"expectedOrderVersion",order.path("version").asInt()));
        assertEquals(200,r.getResponse().getStatus(),r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));return data(r).path("payment");
    }
    private Map<String,Object> notice(JsonNode p,String outcome){return new HashMap<>(Map.of("eventId",UUID.randomUUID().toString(),"paymentId",p.path("id").asText(),"transactionId","mock_"+p.path("id").asText(),"amountTwd",p.path("amountTwd").asLong(),"currency","TWD","outcome",outcome));}
    private MvcResult notify(Map<String,Object> input) throws Exception{return signed(json.writeValueAsString(input),Long.toString(java.time.Instant.now().getEpochSecond()),null);}
    private MvcResult signed(String raw,String ts,String signature) throws Exception{
        return mvc.perform(post("/figura/api/payments/mock/callback").contextPath("/figura").servletPath("/api/payments/mock/callback").header("X-Mock-Timestamp",ts).header("X-Mock-Signature",signature==null?gateway.sign(ts,raw):signature).contentType("application/json").content(raw)).andReturn();
    }
    private int physical(Fixture f){return jdbc.queryForObject("SELECT quantity FROM sku_inventory WHERE sku_id=?",Integer.class,f.sku());}
    private JsonNode paid(Client c,Fixture f,int qty) throws Exception{var o=place(c,ready(c,f,qty));var p=intent(c,o);assertEquals(200,notify(notice(p,"SUCCESS")).getResponse().getStatus());return data(call(c,"GET","/customer/orders/"+o.path("id").asText(),null));}
    private String base(JsonNode o){return "/admin/orders/"+o.path("id").asText();}
    private Map<String,Object> change(JsonNode o){return new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"expectedOrderVersion",o.path("version").asInt(),"note","合成履約說明"));}
    private MvcResult ship(Client actor,JsonNode o,Map<String,Object> body) throws Exception{return call(actor,"POST",base(o)+"/mock-shipment",body);}
    private MvcResult deliver(Client actor,JsonNode o,Map<String,Object> body) throws Exception{return call(actor,"POST",base(o)+"/mock-delivery",body);}
    private JsonNode current(Client actor,JsonNode o) throws Exception{return data(call(actor,"GET",base(o),null)).path("order");}
    private int ledger(JsonNode o){return jdbc.queryForObject("SELECT COUNT(*) FROM order_fulfillment_event WHERE order_id=?",Integer.class,o.path("id").asText());}
    private Client staff() throws Exception{return employee(false);}
    private Client employee(boolean manager) throws Exception{
        var c=guest();String username="fulfill_"+UUID.randomUUID().toString().replace("-","").substring(0,18),password="synthetic-fulfillment-only-password",device=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO user(username,password,is_stop) VALUES(?,?,'F')",username,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(password));int id=jdbc.queryForObject("SELECT id FROM user WHERE username=?",Integer.class,username);actors.add(id);jdbc.update("INSERT INTO user_role(user_id,role_id) VALUES(?,?)",id,manager?1:2);
        var r=mvc.perform(post("/figura/api/admin/login").contextPath("/figura").servletPath("/api/admin/login").cookie(c.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN",c.csrf()).param("username",username).param("password",password).param("deviceId",device)).andReturn();assertEquals(200,r.getResponse().getStatus());sessions.add("shop:session:"+id+":"+device);return new Client(c.csrf(),List.of(c.cookies().get(0),cookie(r,"accessToken"),cookie(r,"refreshToken")),Integer.toString(id));
    }
    private Map<String,Object> application(JsonNode o){return new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"expectedOrderVersion",o.path("version").asInt(),"reason","合成整筆退貨原因"));}
    private MvcResult requestReturn(Client c,JsonNode o,Map<String,Object> input) throws Exception{return call(c,"POST","/customer/orders/"+o.path("id").asText()+"/mock-return",input);}
    private MvcResult receive(Client a,JsonNode o,Map<String,Object> input) throws Exception{return call(a,"POST",base(o)+"/mock-return-receipt",input);}
    private MvcResult reject(Client a,JsonNode o,Map<String,Object> input) throws Exception{return call(a,"POST",base(o)+"/mock-return-rejection",input);}
    private MvcResult refundReturn(Client a,JsonNode o,Map<String,Object> input) throws Exception{return call(a,"POST",base(o)+"/mock-return-refund",input);}
    private Map<String,Object> receipt(JsonNode o,String disposition){var input=change(o);input.put("disposition",disposition);return input;}
    private JsonNode shipped(Client a,Client c,Fixture f,int qty) throws Exception{var o=paid(c,f,qty);assertEquals(200,ship(a,o,change(o)).getResponse().getStatus());return current(a,o);}
    private JsonNode requested(Client a,Client c,JsonNode o) throws Exception{assertEquals(200,requestReturn(c,o,application(o)).getResponse().getStatus());return current(a,o);}
    private JsonNode received(Client a,Client c,JsonNode o,String disposition) throws Exception{o=requested(a,c,o);assertEquals(200,receive(a,o,receipt(o,disposition)).getResponse().getStatus());return current(a,o);}
    private int returnAudits(JsonNode o){return jdbc.queryForObject("SELECT COUNT(*) FROM order_return_event WHERE order_id=?",Integer.class,o.path("id").asText());}
    private int refunds(JsonNode o){return jdbc.queryForObject("SELECT COUNT(*) FROM mock_refund_event WHERE order_id=?",Integer.class,o.path("id").asText());}
    private int stockEvents(JsonNode o,String action){return jdbc.queryForObject("SELECT COUNT(*) FROM order_stock_event WHERE order_id=? AND action=?",Integer.class,o.path("id").asText(),action);}
    private String message(JsonNode o,String kind){return jdbc.queryForObject("SELECT id FROM notification_outbox WHERE order_id=? AND event_kind=?",String.class,o.path("id").asText(),kind);}
    private String route(String id){return "/admin/notifications/"+id;}
    private Map<String,Object> command(String id,String mode){return new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",notifications.detail(id).message().version(),"note","合成通知測試","mode",mode));}
    private MvcResult dispatch(Client a,String id,Map<String,Object> b)throws Exception{return call(a,"POST",route(id)+"/mock-dispatch",b);}
    private void makeDue(String id){jdbc.update("UPDATE notification_outbox SET next_attempt_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",id);}
    private int receipts(String id){return jdbc.queryForObject("SELECT COUNT(*) FROM mock_notification_receipt WHERE delivery_key=?",Integer.class,id);}
    private int count(JsonNode o){return jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE order_id=?",Integer.class,o.path("id").asText());}
    @Test void businessEventsAreAtomicSafeImmutableAndDeduplicated()throws Exception{
        var a=admin();var c=customer();var f=fixture(a,7);var input=ready(c,f,1);var o=place(c,input);assertEquals(1,count(o));assertEquals(200,call(c,"POST","/customer/orders",input).getResponse().getStatus());assertEquals(1,count(o));
        var pay=intent(c,o);var n=notice(pay,"SUCCESS");assertEquals(200,notify(n).getResponse().getStatus());assertEquals(200,notify(n).getResponse().getStatus());assertEquals(200,notify(notice(pay,"SUCCESS")).getResponse().getStatus());o=current(a,o);assertEquals(2,count(o));
        var cmd=change(o);assertEquals(200,ship(a,o,cmd).getResponse().getStatus());assertEquals(200,ship(a,o,cmd).getResponse().getStatus());o=current(a,o);assertEquals(200,deliver(a,o,change(o)).getResponse().getStatus());o=current(a,o);o=received(a,c,o,"RESTOCK");assertEquals(200,refundReturn(a,o,change(o)).getResponse().getStatus());assertEquals(7,count(o));assertEquals(7,physical(f));
        var d=notifications.detail(message(o,"ORDER_PLACED"));var snapshot=json.readTree(d.snapshot());assertEquals(Set.of("orderId","eventKind","orderVersion","orderStatus","fulfillmentStatus","totalTwd"),json.convertValue(snapshot,Map.class).keySet());assertEquals("PENDING_PAYMENT",snapshot.path("orderStatus").asText());assertEquals(1,snapshot.path("orderVersion").asInt());assertFalse(d.snapshot().contains("0912345678"));assertFalse(json.writeValueAsString(d).contains("recipientRef"));assertEquals("PENDING",d.message().status());assertEquals(0,receipts(d.message().id()));
    }
    @Test void failedPaymentReviewCancelExpiryRejectionAndUnshippedRefundAreCapturedOnce()throws Exception{
        var a=admin();var c=customer();var f=fixture(a,20);var o=place(c,ready(c,f,1));var p=intent(c,o);assertEquals(200,notify(notice(p,"FAILURE")).getResponse().getStatus());assertEquals(200,notify(notice(p,"FAILURE")).getResponse().getStatus());assertEquals(200,notify(notice(p,"SUCCESS")).getResponse().getStatus());assertEquals(200,notify(notice(p,"SUCCESS")).getResponse().getStatus());o=current(a,o);assertEquals(3,count(o));assertEquals("PENDING_PAYMENT",json.readTree(notifications.detail(message(o,"PAYMENT_REVIEW_REQUIRED")).snapshot()).path("orderStatus").asText());assertEquals(200,call(a,"POST",base(o)+"/mock-review-refund/"+p.path("id").asText(),change(o)).getResponse().getStatus());o=current(a,o);assertEquals(200,cancel(c,o).getResponse().getStatus());assertEquals(200,cancel(c,o).getResponse().getStatus());assertEquals(5,count(o));
        var expired=place(c,ready(c,f,1));due(expired);orderService.expire(c.id(),expired.path("id").asText());orderService.expire(c.id(),expired.path("id").asText());assertEquals(2,count(expired));assertNotNull(message(expired,"ORDER_EXPIRED"));
        var rejected=shipped(a,c,f,1);rejected=requested(a,c,rejected);assertEquals(200,reject(a,rejected,change(rejected)).getResponse().getStatus());assertEquals(5,count(rejected));assertNotNull(message(rejected,"RETURN_REJECTED"));
        var refund=paid(c,f,1);assertEquals(200,call(a,"POST",base(refund)+"/mock-refund",change(refund)).getResponse().getStatus());assertEquals(3,count(refund));assertNotNull(message(refund,"ORDER_REFUNDED"));
    }
    @Test void outboxInsertFailureRollsBackOrderStockAndCart()throws Exception{
        var a=admin();var c=customer();var f=fixture(a,5);var b=ready(c,f,2);var v=cart(c);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic capture failure")).when(jdbc).update(org.mockito.ArgumentMatchers.startsWith("INSERT INTO notification_outbox"),org.mockito.ArgumentMatchers.any(Object[].class));
        try{assertEquals(500,call(c,"POST","/customer/orders",b).getResponse().getStatus());assertEquals(v,cart(c));assertEquals(0,reserved(f));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM customer_order WHERE request_id=?",Integer.class,b.get("requestId")));}finally{org.mockito.Mockito.reset(jdbc);}
        assertEquals(1,count(place(c,b)));
    }
    @Test void outboxInsertFailureRollsBackPaymentAndCanRetrySameEvent()throws Exception{
        var a=admin();var c=customer();var f=fixture(a,5);var o=place(c,ready(c,f,2));var p=intent(c,o);var n=notice(p,"SUCCESS");
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic capture failure")).when(jdbc).update(org.mockito.ArgumentMatchers.startsWith("INSERT INTO notification_outbox"),org.mockito.ArgumentMatchers.any(Object[].class));
        try{assertEquals(500,notify(n).getResponse().getStatus());assertEquals(5,physical(f));assertEquals(2,reserved(f));assertEquals("PENDING_PAYMENT",current(a,o).path("status").asText());assertEquals(1,count(o));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mock_payment_event WHERE event_id=?",Integer.class,n.get("eventId")));}finally{org.mockito.Mockito.reset(jdbc);}assertEquals(200,notify(n).getResponse().getStatus());assertEquals(2,count(o));
    }
    @Test void acceptedUnknownResultReplaysCommandThenDeduplicatesTransport()throws Exception{
        var a=admin();var c=customer();var f=fixture(a,3);var o=place(c,ready(c,f,1));String id=message(o,"ORDER_PLACED");var b=command(id,"FAIL_AFTER_ACCEPT");var r=dispatch(a,id,b);assertEquals(200,r.getResponse().getStatus());assertEquals("RETRY",data(r).path("current").path("status").asText());assertEquals(1,receipts(id));assertTrue(data(dispatch(a,id,b)).path("replayed").asBoolean());assertEquals(1,notifications.detail(id).message().attempts());assertEquals(409,dispatch(a,id,command(id,"SUCCESS")).getResponse().getStatus());
        var retry=command(id,"SUCCESS");retry.remove("mode");assertEquals(200,call(a,"POST",route(id)+"/retry",retry).getResponse().getStatus());assertEquals(200,dispatch(a,id,command(id,"FAIL_AFTER_ACCEPT")).getResponse().getStatus());var d=notifications.detail(id);assertEquals("SENT",d.message().status());assertEquals(2,d.message().attempts());assertEquals(1,receipts(id));assertEquals("ACCEPTED_REPLAY",d.events().get(2).result());assertEquals("PENDING_PAYMENT",current(a,o).path("status").asText());
    }
    @Test void fiveFailuresBackOffThenDeadAndExplicitRetryPreservesLifetimeCount()throws Exception{
        var a=admin();var c=customer();var o=place(c,ready(c,fixture(a,3),1));var id=message(o,"ORDER_PLACED");for(int i=1;i<=5;i++){makeDue(id);assertEquals(200,dispatch(a,id,command(id,"FAIL_BEFORE")).getResponse().getStatus());var d=notifications.detail(id);assertEquals(i,d.message().cycleAttempts());assertEquals(i,d.message().attempts());assertEquals(i==5?"DEAD":"RETRY",d.message().status());if(i<5)assertTrue(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,updated_at,next_attempt_at) FROM notification_outbox WHERE id=?",Integer.class,id)>= (5<<(i-1))-1);}
        assertEquals(0,receipts(id));assertNull(notifications.detail(id).message().nextAttemptAt());var b=command(id,"SUCCESS");b.remove("mode");assertEquals(200,call(a,"POST",route(id)+"/retry",b).getResponse().getStatus());assertTrue(data(call(a,"POST",route(id)+"/retry",b)).path("replayed").asBoolean());assertEquals(0,notifications.detail(id).message().cycleAttempts());assertEquals(200,dispatch(a,id,command(id,"SUCCESS")).getResponse().getStatus());assertEquals(6,notifications.detail(id).message().attempts());assertEquals(1,receipts(id));
    }
    @Test void auditFailureAfterIndependentAcceptanceRollsBackQueueAndRecovers()throws Exception{
        var a=admin();var c=customer();var o=place(c,ready(c,fixture(a,3),1));var id=message(o,"ORDER_PLACED");var b=command(id,"SUCCESS");
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic finalize failure")).when(jdbc).update(org.mockito.ArgumentMatchers.startsWith("UPDATE notification_event SET"),org.mockito.ArgumentMatchers.any(Object[].class));
        try{assertEquals(500,dispatch(a,id,b).getResponse().getStatus());assertEquals(1,receipts(id));var d=notifications.detail(id);assertEquals(0,d.message().version());assertEquals(0,d.message().attempts());assertTrue(d.events().isEmpty());}finally{org.mockito.Mockito.reset(jdbc);}assertEquals(200,dispatch(a,id,b).getResponse().getStatus());assertEquals("ACCEPTED_REPLAY",notifications.detail(id).events().get(0).result());assertEquals(1,receipts(id));
    }
    @Test void authorizationValidationStaleVersionGlobalCollisionAndImmutableReads()throws Exception{
        var a=admin();var s=staff();var c=customer();var o=place(c,ready(c,fixture(a,3),1));var id=message(o,"ORDER_PLACED");assertEquals(200,call(s,"GET",route(id),null).getResponse().getStatus());assertEquals(403,dispatch(s,id,command(id,"SUCCESS")).getResponse().getStatus());assertEquals(401,call(c,"GET",route(id),null).getResponse().getStatus());var b=command(id,"SUCCESS");var bad=new HashMap<>(b);bad.put("recipient","spoof");assertEquals(400,dispatch(a,id,bad).getResponse().getStatus());bad=new HashMap<>(b);bad.put("mode","EMAIL");assertEquals(400,dispatch(a,id,bad).getResponse().getStatus());bad=new HashMap<>(b);bad.put("expectedVersion",99);assertEquals(409,dispatch(a,id,bad).getResponse().getStatus());assertEquals(0,receipts(id));
        var noCsrf=new Client("",a.cookies(),a.id());assertEquals(403,dispatch(noCsrf,id,b).getResponse().getStatus());assertEquals(200,dispatch(a,id,b).getResponse().getStatus());assertTrue(data(dispatch(a,id,b)).path("replayed").asBoolean());bad=new HashMap<>(b);bad.put("note","changed");assertEquals(409,dispatch(a,id,bad).getResponse().getStatus());var o2=place(c,ready(c,fixture(a,2),1));var id2=message(o2,"ORDER_PLACED");bad=new HashMap<>(b);assertEquals(409,dispatch(a,id2,bad).getResponse().getStatus());assertEquals(0,receipts(id2));assertEquals(400,call(a,"GET","/admin/notifications?status=BAD",null).getResponse().getStatus());assertEquals(400,call(a,"GET","/admin/notifications?orderId=bad",null).getResponse().getStatus());assertEquals(400,call(a,"GET","/admin/notifications?pageSize=51",null).getResponse().getStatus());
    }
    @Test void simultaneousDifferentActorsDispatchOnlyOnceAndStaleLoserHasNoReceipt()throws Exception{
        var a=admin();var b=employee(true);var c=customer();var o=place(c,ready(c,fixture(a,3),1));var id=message(o,"ORDER_PLACED");var x=command(id,"SUCCESS");var y=command(id,"SUCCESS");var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var first=pool.submit(()->{start.await();return dispatch(a,id,x).getResponse().getStatus();});var second=pool.submit(()->{start.await();return dispatch(b,id,y).getResponse().getStatus();});start.countDown();var results=List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));assertTrue(results.contains(200));assertTrue(results.contains(409));assertEquals(1,receipts(id));assertEquals(1,notifications.detail(id).message().attempts());assertEquals(1,notifications.detail(id).events().size());}finally{pool.shutdownNow();}
    }
    @Test void captureRequiresTransactionAndDisabledDeliveryDoesNoWork()throws Exception{
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->capture.enqueue(null,"ORDER_PLACED",UUID.randomUUID().toString()));var disabled=new dev.jackteng.figura.service.NotificationService(null,null,null,false);assertNull(disabled.runOne());assertEquals(404,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->disabled.dispatch(UUID.randomUUID().toString(),null,null)).getStatusCode().value());assertEquals(18,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class));
    }
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Test void workerSkipsLockedMessageAndPersistsSystemAudit()throws Exception{
        var a=admin();var c=customer();var f=fixture(a,3);var o=place(c,ready(c,f,1));var o2=place(c,ready(c,f,1));var id=message(o,"ORDER_PLACED");var second=message(o2,"ORDER_PLACED");jdbc.update("UPDATE notification_outbox SET next_attempt_at='1990-01-01' WHERE id=?",id);jdbc.update("UPDATE notification_outbox SET next_attempt_at='1990-01-02' WHERE id=?",second);
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();try{var held=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{jdbc.queryForObject("SELECT id FROM notification_outbox WHERE id=? FOR UPDATE",String.class,id);locked.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return null;}));assertTrue(locked.await(10,TimeUnit.SECONDS));var result=notifications.runOne();assertEquals(second,result.current().id());assertEquals("SENT",result.current().status());assertNull(result.event().actorId());assertEquals("SYSTEM",result.event().actorUsername());assertEquals(0,receipts(id));release.countDown();held.get(10,TimeUnit.SECONDS);assertEquals(id,notifications.runOne().current().id());assertEquals(1,receipts(id));assertEquals(1,receipts(second));}finally{release.countDown();pool.shutdownNow();}
    }
    @Test void crossMessageSameCommandRaceCannotAcceptLoser()throws Exception{
        var a=admin();var b=employee(true);var c=customer();var f=fixture(a,3);var o=place(c,ready(c,f,1));var o2=place(c,ready(c,f,1));var id=message(o,"ORDER_PLACED");var second=message(o2,"ORDER_PLACED");var x=command(id,"SUCCESS");var y=new HashMap<>(x);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var first=pool.submit(()->{start.await();return dispatch(a,id,x).getResponse().getStatus();});var other=pool.submit(()->{start.await();return dispatch(b,second,y).getResponse().getStatus();});start.countDown();var results=List.of(first.get(15,TimeUnit.SECONDS),other.get(15,TimeUnit.SECONDS));assertTrue(results.contains(200));assertTrue(results.contains(409));assertEquals(1,receipts(id)+receipts(second));assertEquals(1,notifications.detail(id).message().attempts()+notifications.detail(second).message().attempts());}finally{pool.shutdownNow();}
    }
}
