package com.shop.elpsy;

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
class RefundIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired com.shop.elpsy.service.OrderService orderService;
    @Autowired com.shop.elpsy.service.OrderExpiryJob expiry;
    @Autowired com.shop.elpsy.service.MockPaymentGateway gateway;
    @org.springframework.boot.test.mock.mockito.SpyBean com.shop.elpsy.mapper.RefundMapper refundMapper;
    @Autowired com.shop.elpsy.service.RefundService refundService;
    private final Set<Integer> actors=new HashSet<>();
    private final Set<String> customers=ConcurrentHashMap.newKeySet(),products=ConcurrentHashMap.newKeySet(),sessions=ConcurrentHashMap.newKeySet();
    record Client(String csrf,List<Cookie> cookies,String id) {}
    record Fixture(String product,String sku) {String cart(){return "/customer/cart/items/"+sku;}}
    private JsonNode data(MvcResult r) throws Exception{return json.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");}
    private Cookie cookie(MvcResult r,String name){var h=r.getResponse().getHeaders("Set-Cookie").stream().filter(v->v.startsWith(name+"=")).findFirst().orElseThrow();return new Cookie(name,h.split(";",2)[0].substring(name.length()+1));}
    private MvcResult call(Client c,String method,String path,Object body) throws Exception{
        var b=request(org.springframework.http.HttpMethod.valueOf(method),"/ELPSY/api"+path).contextPath("/ELPSY").servletPath("/api"+path.split("\\?",2)[0]);
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
        var c=guest();String device=UUID.randomUUID().toString();var r=mvc.perform(post("/ELPSY/api/admin/login").contextPath("/ELPSY").servletPath("/api/admin/login").cookie(c.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN",c.csrf()).param("username","shop_admin").param("password",System.getenv("DEMO_ADMIN_PASSWORD")).param("deviceId",device)).andReturn();assertEquals(200,r.getResponse().getStatus());String id=data(r).path("id").asText();sessions.add("shop:session:"+id+":"+device);
        return new Client(c.csrf(),List.of(c.cookies().get(0),cookie(r,"accessToken"),cookie(r,"refreshToken")),id);
    }
    private Fixture fixture(Client admin,int stock) throws Exception{
        var r=call(admin,"POST","/admin/products",Map.of("title","Shopping "+UUID.randomUUID(),"brand","ELPSY","category","公仔","description","合成商品","expectedVersion",0,"skus",List.of(Map.of("code","CART-"+UUID.randomUUID(),"label","標準款","priceTwd",1280,"enabled",true))));assertEquals(201,r.getResponse().getStatus());var p=data(r);var f=new Fixture(p.path("id").asText(),p.path("skus").get(0).path("id").asText());products.add(f.product());
        if(stock>0){r=call(admin,"POST","/admin/products/"+f.product()+"/skus/"+f.sku()+"/inventory/adjustments",Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",0,"delta",stock,"reason","RESTOCK","note","合成購物車測試入庫"));assertEquals(201,r.getResponse().getStatus());}
        assertEquals(200,call(admin,"PATCH","/admin/products/"+f.product()+"/publication",Map.of("expectedVersion",0,"published",true)).getResponse().getStatus());return f;
    }
    private JsonNode cart(Client c) throws Exception{var r=call(c,"GET","/customer/cart",null);assertEquals(200,r.getResponse().getStatus());return data(r);}
    private Map<String,Object> quantity(int version,int quantity){return new HashMap<>(Map.of("expectedVersion",version,"quantity",quantity));}
    private Map<String,Object> address(int version){return new HashMap<>(Map.of("expectedVersion",version,"recipientName","合成收件人","phone","0912345678","postalCode","100","city","測試市","district","測試區","street","合成路 1 號"));}
    @AfterEach void cleanup(){
        org.mockito.Mockito.reset(refundMapper);
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
        return mvc.perform(post("/ELPSY/api/payments/mock/callback").contextPath("/ELPSY").servletPath("/api/payments/mock/callback").header("X-Mock-Timestamp",ts).header("X-Mock-Signature",signature==null?gateway.sign(ts,raw):signature).contentType("application/json").content(raw)).andReturn();
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
        var r=mvc.perform(post("/ELPSY/api/admin/login").contextPath("/ELPSY").servletPath("/api/admin/login").cookie(c.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN",c.csrf()).param("username",username).param("password",password).param("deviceId",device)).andReturn();assertEquals(200,r.getResponse().getStatus());sessions.add("shop:session:"+id+":"+device);return new Client(c.csrf(),List.of(c.cookies().get(0),cookie(r,"accessToken"),cookie(r,"refreshToken")),Integer.toString(id));
    }
    private MvcResult refund(Client a,JsonNode o,Map<String,Object> input) throws Exception{return call(a,"POST",base(o)+"/mock-refund",input);}
    private MvcResult review(Client a,JsonNode o,JsonNode p,Map<String,Object> input) throws Exception{return call(a,"POST",base(o)+"/mock-review-refund/"+p.path("id").asText(),input);}
    private int refunds(JsonNode o){return jdbc.queryForObject("SELECT COUNT(*) FROM mock_refund_event WHERE order_id=?",Integer.class,o.path("id").asText());}
    @Test void fullRefundReturnsPhysicalStockOnceAndKeepsOriginalPaymentAndSnapshots() throws Exception{
        var a=admin();var f=fixture(a,7);var c=customer();var o=paid(c,f,2);var request=change(o);var paidAt=o.path("paidAt");int stockVersion=jdbc.queryForObject("SELECT version FROM sku_inventory WHERE sku_id=?",Integer.class,f.sku());var r=refund(a,o,request);assertEquals(200,r.getResponse().getStatus(),r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));var end=data(r).path("order");assertEquals("REFUNDED",end.path("status").asText());assertEquals(3,end.path("version").asInt());assertEquals(2560,end.path("refund").path("amountTwd").asLong());assertEquals("MOCK",end.path("refund").path("provider").asText());assertEquals(paidAt,end.path("paidAt"));assertEquals(o.path("shipping"),end.path("shipping"));assertEquals(o.path("items"),end.path("items"));assertEquals(7,physical(f));assertEquals(0,reserved(f));assertEquals(stockVersion+1,jdbc.queryForObject("SELECT version FROM sku_inventory WHERE sku_id=?",Integer.class,f.sku()));assertEquals(1,refunds(o));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM order_stock_event WHERE order_id=? AND action='RETURN'",Integer.class,o.path("id").asText()));
        assertEquals(200,refund(a,o,request).getResponse().getStatus());assertTrue(data(refund(a,o,request)).path("replayed").asBoolean());assertEquals(7,physical(f));assertEquals(1,refunds(o));assertEquals(409,refund(a,end,change(end)).getResponse().getStatus());assertEquals(409,ship(a,end,change(end)).getResponse().getStatus());var p=data(call(c,"GET","/customer/orders/"+o.path("id").asText()+"/mock-payments",null)).path("items").get(0);assertEquals("SUCCEEDED",p.path("status").asText());assertEquals(200,notify(notice(p,"SUCCESS")).getResponse().getStatus());assertEquals("REFUNDED",current(a,o).path("status").asText());assertEquals(7,physical(f));
        var customerView=data(call(c,"GET","/customer/orders/"+o.path("id").asText(),null));assertEquals(end,customerView);assertFalse(customerView.toString().contains("actorUsername"));assertFalse(customerView.toString().contains(request.get("note").toString()));assertEquals(404,call(customer(),"GET","/customer/orders/"+o.path("id").asText(),null).getResponse().getStatus());
    }
    @Test void lateSuccessAfterCancellationIsReviewedWithoutResurrectionOrStockReturn() throws Exception{
        var a=admin();var f=fixture(a,7);var c=customer();var o=place(c,ready(c,f,2));var p=intent(c,o);assertEquals(200,cancel(c,o).getResponse().getStatus());assertEquals(200,notify(notice(p,"SUCCESS")).getResponse().getStatus());o=current(a,o);var input=change(o);int stockVersion=jdbc.queryForObject("SELECT version FROM sku_inventory WHERE sku_id=?",Integer.class,f.sku());var r=review(a,o,p,input);assertEquals(200,r.getResponse().getStatus());var end=data(r).path("order");assertEquals("CANCELLED",end.path("status").asText());assertTrue(end.path("refund").isNull());assertEquals(3,end.path("version").asInt());assertEquals(7,physical(f));assertEquals(stockVersion,jdbc.queryForObject("SELECT version FROM sku_inventory WHERE sku_id=?",Integer.class,f.sku()));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM order_stock_event WHERE order_id=? AND action IN ('CONSUME','RETURN')",Integer.class,o.path("id").asText()));assertEquals(200,review(a,o,p,input).getResponse().getStatus());assertTrue(data(review(a,o,p,input)).path("replayed").asBoolean());assertEquals(200,notify(notice(p,"SUCCESS")).getResponse().getStatus());var details=data(call(a,"GET",base(o)+"/refunds",null));assertTrue(details.path("reviews").get(0).path("resolved").asBoolean());assertEquals("SUCCESS",details.path("reviews").get(0).path("notifications").get(0).path("outcome").asText());assertEquals("REVIEW_REQUIRED",details.path("reviews").get(0).path("notifications").get(0).path("disposition").asText());var safe=data(call(c,"GET","/customer/orders/"+o.path("id").asText()+"/mock-payments",null)).path("items").get(0);assertEquals("REFUNDED",safe.path("refund").path("status").asText());assertFalse(safe.toString().contains("actorUsername"));assertEquals("REVIEW_REQUIRED",jdbc.queryForObject("SELECT status FROM mock_payment WHERE id=?",String.class,p.path("id").asText()));assertEquals(1,refunds(o));
    }
    @Test void extraContradictoryPaymentFreezesFulfillmentUntilReviewedThenPrimaryRefundRestocksOnlyOnce() throws Exception{
        var a=admin();var f=fixture(a,8);var c=customer();var o=place(c,ready(c,f,2));var first=intent(c,o);assertEquals(200,notify(notice(first,"FAILURE")).getResponse().getStatus());var second=intent(c,o);assertEquals(200,notify(notice(second,"SUCCESS")).getResponse().getStatus());assertEquals(200,notify(notice(first,"SUCCESS")).getResponse().getStatus());o=current(a,o);assertEquals(409,refund(a,o,change(o)).getResponse().getStatus());assertEquals(409,ship(a,o,change(o)).getResponse().getStatus());assertEquals(200,review(a,o,first,change(o)).getResponse().getStatus());var end=current(a,o);assertEquals(6,physical(f));assertEquals("PAID",end.path("status").asText());assertTrue(data(call(a,"GET",base(o),null)).path("canShip").asBoolean());assertEquals(200,refund(a,end,change(end)).getResponse().getStatus());assertEquals(8,physical(f));assertEquals(2,refunds(o));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM order_stock_event WHERE order_id=? AND action='RETURN'",Integer.class,o.path("id").asText()));
    }
    @Test void reviewOfPendingFailedIntentAllowsFreshPaymentButNeverReusesRefundedIntent() throws Exception{
        var a=admin();var f=fixture(a,7);var c=customer();var o=place(c,ready(c,f,2));var p=intent(c,o);assertEquals(200,notify(notice(p,"FAILURE")).getResponse().getStatus());assertEquals(200,notify(notice(p,"SUCCESS")).getResponse().getStatus());assertEquals(409,call(c,"POST","/customer/orders/"+o.path("id").asText()+"/mock-payments",Map.of("requestId",UUID.randomUUID().toString(),"expectedOrderVersion",1)).getResponse().getStatus());assertEquals(200,review(a,o,p,change(o)).getResponse().getStatus());o=current(a,o);assertEquals("PENDING_PAYMENT",o.path("status").asText());assertEquals(7,physical(f));assertEquals(2,reserved(f));assertEquals(200,notify(notice(p,"SUCCESS")).getResponse().getStatus());assertEquals("PENDING_PAYMENT",current(a,o).path("status").asText());var next=intent(c,o);assertEquals(200,notify(notice(next,"SUCCESS")).getResponse().getStatus());assertEquals("PAID",current(a,o).path("status").asText());assertEquals(5,physical(f));assertEquals(0,reserved(f));assertEquals(1,refunds(o));
    }
    @Test void shippedOrdersCannotRefundButExtraPaymentCanBeReviewedToResumeDelivery() throws Exception{
        var a=admin();var f=fixture(a,7);var c=customer();var o=place(c,ready(c,f,1));var p=intent(c,o);notify(notice(p,"FAILURE"));var next=intent(c,o);notify(notice(next,"SUCCESS"));o=current(a,o);assertEquals(200,ship(a,o,change(o)).getResponse().getStatus());notify(notice(p,"SUCCESS"));o=current(a,o);assertEquals(409,refund(a,o,change(o)).getResponse().getStatus());assertEquals(409,deliver(a,o,change(o)).getResponse().getStatus());assertEquals(200,review(a,o,p,change(o)).getResponse().getStatus());o=current(a,o);assertEquals(200,deliver(a,o,change(o)).getResponse().getStatus());assertEquals(6,physical(f));assertEquals("DELIVERED",current(a,o).path("fulfillment").path("status").asText());
    }
    @Test void identicalRefundRaceReplaysWhileShipRefundRaceHasSingleWinner() throws Exception{
        var a=admin();var f=fixture(a,8);var c=customer();var o=paid(c,f,2);var input=change(o);assertEquals(List.of(200,200),race(()->refund(a,o,input).getResponse().getStatus(),()->refund(a,o,input).getResponse().getStatus()));assertEquals(1,refunds(o));assertEquals(8,physical(f));var next=paid(c,f,2);var one=change(next);var two=change(next);assertEquals(Set.of(200,409),new HashSet<>(race(()->refund(a,next,one).getResponse().getStatus(),()->ship(a,next,two).getResponse().getStatus())));var end=current(a,next);assertEquals(end.path("status").asText().equals("REFUNDED")?8:6,physical(f));assertEquals(1,refunds(next)+ledger(next));
    }
    @Test void refundAndReviewAuditFailuresRollbackAndSameRequestRetries() throws Exception{
        var a=admin();var f=fixture(a,7);var c=customer();var o=paid(c,f,2);var input=change(o);org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic refund audit failure")).when(refundMapper).append(org.mockito.ArgumentMatchers.any());assertEquals(500,refund(a,o,input).getResponse().getStatus());org.mockito.Mockito.reset(refundMapper);assertEquals(o,current(a,o));assertEquals(5,physical(f));assertEquals(0,refunds(o));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM order_stock_event WHERE order_id=? AND action='RETURN'",Integer.class,o.path("id").asText()));assertEquals(200,refund(a,o,input).getResponse().getStatus());assertEquals(7,physical(f));
        var pending=place(c,ready(c,f,1));var p=intent(c,pending);notify(notice(p,"FAILURE"));notify(notice(p,"SUCCESS"));var request=change(pending);org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic review audit failure")).when(refundMapper).append(org.mockito.ArgumentMatchers.any());assertEquals(500,review(a,pending,p,request).getResponse().getStatus());org.mockito.Mockito.reset(refundMapper);assertFalse(data(call(a,"GET",base(pending)+"/refunds",null)).path("reviews").get(0).path("resolved").asBoolean());assertEquals(1,current(a,pending).path("version").asInt());assertEquals(200,review(a,pending,p,request).getResponse().getStatus());
    }
    @Test void capacityOverflowRollsBackRefundAndCanRetryAfterInventoryCorrection() throws Exception{
        var a=admin();var f=fixture(a,7);var c=customer();var o=paid(c,f,2);var input=change(o);var path="/admin/products/"+f.product()+"/skus/"+f.sku()+"/inventory";int version=jdbc.queryForObject("SELECT version FROM sku_inventory WHERE sku_id=?",Integer.class,f.sku());assertEquals(201,call(a,"POST",path+"/adjustments",Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",version,"delta",999995,"reason","RESTOCK","note","合成上限測試")).getResponse().getStatus());assertEquals(409,refund(a,o,input).getResponse().getStatus());assertEquals(o,current(a,o));assertEquals(1000000,physical(f));assertEquals(0,refunds(o));version=jdbc.queryForObject("SELECT version FROM sku_inventory WHERE sku_id=?",Integer.class,f.sku());assertEquals(201,call(a,"POST",path+"/adjustments",Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",version,"delta",-2,"reason","CORRECTION","note","合成容量修正")).getResponse().getStatus());assertEquals(200,refund(a,o,input).getResponse().getStatus());assertEquals(1000000,physical(f));
    }
    @Test void permissionRealmCsrfUnknownFieldsAndRevokedActorAreGuarded() throws Exception{
        var a=admin();var f=fixture(a,7);var c=customer();var o=paid(c,f,1);var input=change(o);var employee=staff();assertEquals(401,call(c,"GET",base(o)+"/refunds",null).getResponse().getStatus());assertEquals(401,call(guest(),"GET",base(o)+"/refunds",null).getResponse().getStatus());assertEquals(200,call(employee,"GET",base(o)+"/refunds",null).getResponse().getStatus());assertEquals(403,refund(employee,o,input).getResponse().getStatus());assertEquals(403,refund(new Client("wrong",a.cookies(),a.id()),o,input).getResponse().getStatus());for(String name:List.of("amountTwd","actorId","customerId","status")){var bad=new HashMap<>(input);bad.put(name,"forged");assertEquals(400,refund(a,o,bad).getResponse().getStatus());}var bad=new HashMap<>(input);bad.put("expectedOrderVersion",2.5);assertEquals(400,refund(a,o,bad).getResponse().getStatus());bad=new HashMap<>(input);bad.put("note"," ");assertEquals(400,refund(a,o,bad).getResponse().getStatus());assertEquals(403,call(a,"DELETE",base(o)+"/refunds",null).getResponse().getStatus());
        var manager=employee(true);int id=Integer.parseInt(manager.id());var principal=new com.shop.elpsy.pojo.CustomUserDetails("forged",id,List.of());var request=new com.shop.elpsy.dto.FulfillmentPayloads.Change(UUID.randomUUID().toString(),2,"合成說明");jdbc.update("DELETE FROM user_role WHERE user_id=?",id);jdbc.update("INSERT INTO user_role VALUES(?,2)",id);assertEquals(403,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->refundService.change(o.path("id").asText(),null,request,principal)).getStatusCode().value());jdbc.update("UPDATE user SET is_stop='T' WHERE id=?",id);assertEquals(401,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->refundService.change(o.path("id").asText(),null,request,principal)).getStatusCode().value());assertEquals(0,refunds(o));
    }
    @Test void replayRejectsChangedContentActorOrderAndAction() throws Exception{
        var a=admin();var f=fixture(a,7);var c=customer();var o=paid(c,f,1);var input=change(o);assertEquals(200,refund(a,o,input).getResponse().getStatus());assertEquals(409,refund(employee(true),o,input).getResponse().getStatus());var next=paid(c,f,1);assertEquals(409,refund(a,next,input).getResponse().getStatus());var bad=new HashMap<>(input);bad.put("note","different");assertEquals(409,refund(a,o,bad).getResponse().getStatus());bad=new HashMap<>(input);bad.put("expectedOrderVersion",3);assertEquals(409,refund(a,o,bad).getResponse().getStatus());var p=data(call(c,"GET","/customer/orders/"+o.path("id").asText()+"/mock-payments",null)).path("items").get(0);assertEquals(409,review(a,o,p,input).getResponse().getStatus());assertEquals(1,refunds(o));assertEquals(0,refunds(next));
    }
    @Test void reviewCannotTargetForeignOrNonReviewedPaymentAndGlobalCollisionRollsBack() throws Exception{
        var a=admin();var f=fixture(a,8);var c=customer();var one=paid(c,f,1);var two=paid(c,f,1);var p=data(call(c,"GET","/customer/orders/"+one.path("id").asText()+"/mock-payments",null)).path("items").get(0);assertEquals(404,review(a,two,p,change(two)).getResponse().getStatus());assertEquals(409,review(a,one,p,change(one)).getResponse().getStatus());var other=employee(true);var input=change(one);var second=change(two);second.put("requestId",input.get("requestId"));assertEquals(Set.of(200,409),new HashSet<>(race(()->refund(a,one,input).getResponse().getStatus(),()->refund(other,two,second).getResponse().getStatus())));assertEquals(1,refunds(one)+refunds(two));assertEquals(7,physical(f));
    }
    @Test void archivedSourcesAndDisabledCustomerStillUsePaidSnapshotAndRefundedStateHasDbGuard() throws Exception{
        var a=admin();var f=fixture(a,7);var c=customer();var o=paid(c,f,1);jdbc.update("UPDATE catalog_product SET status='ARCHIVED' WHERE id=?",f.product());jdbc.update("UPDATE customer SET enabled=FALSE WHERE id=?",c.id());assertEquals(200,refund(a,o,change(o)).getResponse().getStatus());assertEquals(o.path("shipping"),current(a,o).path("shipping"));assertEquals(7,physical(f));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE customer_order SET fulfillment_status='SHIPPED',shipment_tracking_id=?,shipped_at=CURRENT_TIMESTAMP(6) WHERE id=?","MOCK-"+UUID.randomUUID(),o.path("id").asText()));assertEquals(18,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class));
    }
    @Test void inventoryAdjustmentAndRefundUseCompatibleActorFirstLocks() throws Exception{
        var a=admin();var f=fixture(a,7);var c=customer();var o=paid(c,f,2);var input=change(o);int version=jdbc.queryForObject("SELECT version FROM sku_inventory WHERE sku_id=?",Integer.class,f.sku());String path="/admin/products/"+f.product()+"/skus/"+f.sku()+"/inventory/adjustments";var adjustment=Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",version,"delta",1,"reason","RESTOCK","note","合成退款競爭入庫");
        var statuses=race(()->refund(a,o,input).getResponse().getStatus(),()->call(a,"POST",path,adjustment).getResponse().getStatus());assertEquals(200,statuses.get(0));assertTrue(Set.of(201,409).contains(statuses.get(1)),statuses.toString());assertEquals(statuses.get(1)==201?8:7,physical(f));assertEquals(0,reserved(f));assertEquals(1,refunds(o));assertEquals("REFUNDED",current(a,o).path("status").asText());
    }
    private List<Integer> race(Callable<Integer> a,Callable<Integer> b) throws Exception{var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var one=pool.submit(()->{start.await();return a.call();});var two=pool.submit(()->{start.await();return b.call();});start.countDown();return List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
}
