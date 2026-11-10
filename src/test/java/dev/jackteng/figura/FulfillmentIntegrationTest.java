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
class FulfillmentIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired dev.jackteng.figura.service.OrderService orderService;
    @Autowired dev.jackteng.figura.service.OrderExpiryJob expiry;
    @Autowired dev.jackteng.figura.service.MockPaymentGateway gateway;
    @org.springframework.boot.test.mock.mockito.SpyBean dev.jackteng.figura.mapper.FulfillmentMapper fulfillmentMapper;
    @Autowired dev.jackteng.figura.service.FulfillmentService fulfillmentService;
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
        var r=call(admin,"POST","/admin/products",Map.of("title","Shopping "+UUID.randomUUID(),"brand","ELPSY","category","公仔","description","合成商品","expectedVersion",0,"skus",List.of(Map.of("code","CART-"+UUID.randomUUID(),"label","標準款","priceTwd",1280,"enabled",true))));assertEquals(201,r.getResponse().getStatus());var p=data(r);var f=new Fixture(p.path("id").asText(),p.path("skus").get(0).path("id").asText());products.add(f.product());
        if(stock>0){r=call(admin,"POST","/admin/products/"+f.product()+"/skus/"+f.sku()+"/inventory/adjustments",Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",0,"delta",stock,"reason","RESTOCK","note","合成購物車測試入庫"));assertEquals(201,r.getResponse().getStatus());}
        assertEquals(200,call(admin,"PATCH","/admin/products/"+f.product()+"/publication",Map.of("expectedVersion",0,"published",true)).getResponse().getStatus());return f;
    }
    private JsonNode cart(Client c) throws Exception{var r=call(c,"GET","/customer/cart",null);assertEquals(200,r.getResponse().getStatus());return data(r);}
    private Map<String,Object> quantity(int version,int quantity){return new HashMap<>(Map.of("expectedVersion",version,"quantity",quantity));}
    private Map<String,Object> address(int version){return new HashMap<>(Map.of("expectedVersion",version,"recipientName","合成收件人","phone","0912345678","postalCode","100","city","測試市","district","測試區","street","合成路 1 號"));}
    @AfterEach void cleanup(){
        org.mockito.Mockito.reset(fulfillmentMapper);
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
    @Test void shipAndDeliveryProgressIsSeparateFromPaidAndNeverDeductsStockAgain() throws Exception{
        var admin=admin();var f=fixture(admin,7);var c=customer();var o=paid(c,f,2);var paidAt=o.path("paidAt");int stockVersion=jdbc.queryForObject("SELECT version FROM sku_inventory WHERE sku_id=?",Integer.class,f.sku());var body=change(o);var r=ship(admin,o,body);assertEquals(200,r.getResponse().getStatus());var shipped=data(r).path("current").path("order");assertEquals("PAID",shipped.path("status").asText());assertEquals(3,shipped.path("version").asInt());assertEquals("SHIPPED",shipped.path("fulfillment").path("status").asText());assertTrue(shipped.path("fulfillment").path("trackingId").asText().startsWith("MOCK-"));assertFalse(shipped.path("fulfillment").path("shippedAt").isNull());
        var shipment=shipped.path("fulfillment");assertEquals(200,deliver(admin,shipped,change(shipped)).getResponse().getStatus());var end=current(admin,o);assertEquals("DELIVERED",end.path("fulfillment").path("status").asText());assertEquals(4,end.path("version").asInt());assertEquals(paidAt,end.path("paidAt"));assertEquals(shipment.path("trackingId"),end.path("fulfillment").path("trackingId"));assertEquals(shipment.path("shippedAt"),end.path("fulfillment").path("shippedAt"));assertFalse(end.path("fulfillment").path("deliveredAt").isNull());assertEquals(5,physical(f));assertEquals(0,reserved(f));assertEquals(stockVersion,jdbc.queryForObject("SELECT version FROM sku_inventory WHERE sku_id=?",Integer.class,f.sku()));assertEquals(2,ledger(o));assertEquals(409,cancel(c,end).getResponse().getStatus());
        var audit=data(call(admin,"GET",base(o)+"/fulfillment-events",null));assertEquals("shop_admin",audit.get(0).path("actorUsername").asText());assertEquals(2,audit.get(0).path("beforeVersion").asInt());assertEquals(3,audit.get(1).path("beforeVersion").asInt());var customerView=data(call(c,"GET","/customer/orders/"+o.path("id").asText(),null));assertEquals(end,customerView);assertFalse(customerView.toString().contains("actorUsername"));assertFalse(customerView.toString().contains("合成履約說明"));
    }
    @Test void realmPermissionsCsrfUnknownFieldsAndAuditMutationsAreRejected() throws Exception{
        var admin=admin();var f=fixture(admin,7);var c=customer();var o=paid(c,f,2);var employee=staff();String path=base(o);var body=change(o);
        assertEquals(401,call(guest(),"GET","/admin/orders",null).getResponse().getStatus());assertEquals(401,call(c,"GET",path,null).getResponse().getStatus());assertEquals(200,call(employee,"GET",path,null).getResponse().getStatus());assertEquals(200,call(employee,"GET",path+"/fulfillment-events",null).getResponse().getStatus());assertEquals(403,ship(employee,o,body).getResponse().getStatus());assertEquals(403,deliver(employee,o,body).getResponse().getStatus());assertEquals(403,ship(new Client("wrong",admin.cookies(),admin.id()),o,body).getResponse().getStatus());
        for(String field:List.of("actorId","owner","trackingId","status")){var bad=new HashMap<>(body);bad.put(field,"forged");assertEquals(400,ship(admin,o,bad).getResponse().getStatus());}var bad=new HashMap<>(body);bad.put("expectedOrderVersion",1.5);assertEquals(400,ship(admin,o,bad).getResponse().getStatus());bad.put("expectedOrderVersion",2);bad.put("note"," ");assertEquals(400,ship(admin,o,bad).getResponse().getStatus());
        assertEquals(403,call(admin,"DELETE",path+"/fulfillment-events",null).getResponse().getStatus());assertEquals(403,call(admin,"PUT",path+"/fulfillment-events",body).getResponse().getStatus());assertEquals(403,call(admin,"POST","/admin/orders",body).getResponse().getStatus());assertEquals(0,ledger(o));assertEquals(5,physical(f));
    }
    @Test void unpaidClosedUnshippedAndPaymentReviewCannotAdvance() throws Exception{
        var admin=admin();var f=fixture(admin,9);var c=customer();var o=place(c,ready(c,f,1));assertEquals(409,ship(admin,o,change(o)).getResponse().getStatus());assertEquals(200,cancel(c,o).getResponse().getStatus());o=data(call(c,"GET","/customer/orders/"+o.path("id").asText(),null));assertEquals(409,ship(admin,o,change(o)).getResponse().getStatus());
        var expired=place(c,ready(c,f,1));due(expired);orderService.expire(c.id(),expired.path("id").asText());assertEquals(409,ship(admin,current(admin,expired),change(current(admin,expired))).getResponse().getStatus());
        var reviewed=place(c,ready(c,f,1));var first=intent(c,reviewed);assertEquals(200,notify(notice(first,"FAILURE")).getResponse().getStatus());var next=intent(c,reviewed);assertEquals(200,notify(notice(next,"SUCCESS")).getResponse().getStatus());reviewed=current(admin,reviewed);assertEquals(409,deliver(admin,reviewed,change(reviewed)).getResponse().getStatus());assertEquals(200,notify(notice(first,"SUCCESS")).getResponse().getStatus());assertFalse(data(call(admin,"GET",base(reviewed),null)).path("canShip").asBoolean());assertEquals(409,ship(admin,reviewed,change(reviewed)).getResponse().getStatus());assertEquals(0,ledger(reviewed));assertEquals(8,physical(f));var inFlight=place(c,ready(c,f,1));var failed=intent(c,inFlight);assertEquals(200,notify(notice(failed,"FAILURE")).getResponse().getStatus());var successful=intent(c,inFlight);assertEquals(200,notify(notice(successful,"SUCCESS")).getResponse().getStatus());inFlight=current(admin,inFlight);assertEquals(200,ship(admin,inFlight,change(inFlight)).getResponse().getStatus());assertEquals(200,notify(notice(failed,"SUCCESS")).getResponse().getStatus());var stopped=current(admin,inFlight);assertEquals(409,deliver(admin,stopped,change(stopped)).getResponse().getStatus());assertEquals("SHIPPED",stopped.path("fulfillment").path("status").asText());assertEquals(1,ledger(stopped));
    }
    @Test void concurrentIdenticalShipRequestReplaysOneImmutableEvent() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=paid(c,f,1);var body=change(o);assertEquals(List.of(200,200),race(()->ship(admin,o,body).getResponse().getStatus(),()->ship(admin,o,body).getResponse().getStatus()));assertEquals(1,ledger(o));assertEquals(4,physical(f));assertTrue(data(ship(admin,o,body)).path("replayed").asBoolean());
    }
    @Test void differentConcurrentShipRequestsOnlyOneCanAdvance() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=paid(c,f,1);var one=change(o);var two=change(o);assertEquals(Set.of(200,409),new HashSet<>(race(()->ship(admin,o,one).getResponse().getStatus(),()->ship(admin,o,two).getResponse().getStatus())));assertEquals(1,ledger(o));assertEquals(4,physical(f));assertEquals(3,current(admin,o).path("version").asInt());
    }
    @Test void deliveryRepeatAndHistoricalShipReplayKeepCurrentDeliveredProgress() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=paid(c,f,1);var initial=change(o);assertEquals(200,ship(admin,o,initial).getResponse().getStatus());var shipped=current(admin,o);var body=change(shipped);assertEquals(List.of(200,200),race(()->deliver(admin,shipped,body).getResponse().getStatus(),()->deliver(admin,shipped,body).getResponse().getStatus()));assertEquals(409,deliver(admin,current(admin,o),change(current(admin,o))).getResponse().getStatus());var replay=data(ship(admin,o,initial));assertTrue(replay.path("replayed").asBoolean());assertEquals("SHIPPED",replay.path("event").path("afterStatus").asText());assertEquals("DELIVERED",replay.path("current").path("order").path("fulfillment").path("status").asText());assertEquals(2,ledger(o));
    }
    @Test void globalRequestCollisionAcrossDifferentActorsRollsBackLosingOrder() throws Exception{
        var admin=admin();var other=employee(true);var f=fixture(admin,8);var a=customer();var b=customer();var one=paid(a,f,1);var two=paid(b,f,1);var first=change(one);var second=change(two);second.put("requestId",first.get("requestId"));assertEquals(Set.of(200,409),new HashSet<>(race(()->ship(admin,one,first).getResponse().getStatus(),()->ship(other,two,second).getResponse().getStatus())));assertEquals(1,ledger(one)+ledger(two));assertEquals(1,java.util.stream.Stream.of(current(admin,one),current(admin,two)).filter(o->o.path("fulfillment").path("status").asText().equals("SHIPPED")).count());assertEquals(6,physical(f));
    }
    @Test void replayCannotChangeActorOrderActionVersionOrNote() throws Exception{
        var admin=admin();var other=employee(true);var f=fixture(admin,5);var c=customer();var o=paid(c,f,1);var second=paid(c,f,1);var body=change(o);assertEquals(200,ship(admin,o,body).getResponse().getStatus());assertEquals(409,ship(other,o,body).getResponse().getStatus());assertEquals(409,ship(admin,second,body).getResponse().getStatus());assertEquals(409,deliver(admin,current(admin,o),body).getResponse().getStatus());var bad=new HashMap<>(body);bad.put("note","changed");assertEquals(409,ship(admin,o,bad).getResponse().getStatus());bad=new HashMap<>(body);bad.put("expectedOrderVersion",3);assertEquals(409,ship(admin,o,bad).getResponse().getStatus());assertEquals(1,ledger(o));assertEquals(0,ledger(second));
    }
    @Test void shipAndDeliveryAuditFailureRollBackAllProgressAndSameRequestCanRetry() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=paid(c,f,1);var body=change(o);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic fulfillment audit failure")).when(fulfillmentMapper).append(org.mockito.ArgumentMatchers.any());assertEquals(500,ship(admin,o,body).getResponse().getStatus());org.mockito.Mockito.reset(fulfillmentMapper);assertEquals(2,current(admin,o).path("version").asInt());assertEquals("UNFULFILLED",current(admin,o).path("fulfillment").path("status").asText());assertTrue(current(admin,o).path("fulfillment").path("trackingId").isNull());assertEquals(0,ledger(o));assertEquals(200,ship(admin,o,body).getResponse().getStatus());
        var shipped=current(admin,o);var next=change(shipped);org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic delivery audit failure")).when(fulfillmentMapper).append(org.mockito.ArgumentMatchers.any());assertEquals(500,deliver(admin,shipped,next).getResponse().getStatus());org.mockito.Mockito.reset(fulfillmentMapper);assertEquals(shipped,current(admin,o));assertEquals(1,ledger(o));assertEquals(200,deliver(admin,shipped,next).getResponse().getStatus());assertEquals(2,ledger(o));assertEquals(4,physical(f));
    }
    @Test void serviceRechecksRevokedAndDisabledActorBeforeWritingEvenWithOldPrincipal() throws Exception{
        var admin=admin();var actor=employee(true);var f=fixture(admin,5);var c=customer();var o=paid(c,f,1);int id=Integer.parseInt(actor.id());var principal=new dev.jackteng.figura.pojo.CustomUserDetails("forged-name",id,List.of());var input=new dev.jackteng.figura.dto.FulfillmentPayloads.Change(UUID.randomUUID().toString(),2,"合成說明");jdbc.update("DELETE FROM user_role WHERE user_id=?",id);jdbc.update("INSERT INTO user_role VALUES(?,2)",id);assertEquals(403,ship(actor,o,change(o)).getResponse().getStatus());assertEquals(403,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->fulfillmentService.change(o.path("id").asText(),"SHIP",input,principal)).getStatusCode().value());jdbc.update("DELETE FROM user_role WHERE user_id=?",id);jdbc.update("INSERT INTO user_role VALUES(?,1)",id);jdbc.update("UPDATE user SET is_stop='T' WHERE id=?",id);assertEquals(401,ship(actor,o,change(o)).getResponse().getStatus());assertEquals(401,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->fulfillmentService.change(o.path("id").asText(),"SHIP",input,principal)).getStatusCode().value());assertEquals(0,ledger(o));
    }
    @Test void archivedProductDisabledCustomerAndDeletedSourceAddressDoNotRewritePaidSnapshot() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=paid(c,f,1);jdbc.update("UPDATE catalog_product SET status='ARCHIVED' WHERE id=?",f.product());jdbc.update("UPDATE customer SET enabled=FALSE WHERE id=?",c.id());jdbc.update("UPDATE customer_address SET deleted=TRUE,recipient_name='',phone='',postal_code='',city='',district='',street='' WHERE customer_id=?",c.id());assertEquals(200,ship(admin,o,change(o)).getResponse().getStatus());var end=current(admin,o);assertEquals(o.path("shipping"),end.path("shipping"));assertEquals(o.path("items"),end.path("items"));assertEquals(4,physical(f));
    }
    @Test void boundedFilteredListIsSummaryOnlyAndVersion256IsAccepted() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=paid(c,f,1);jdbc.update("UPDATE customer_order SET version=256 WHERE id=?",o.path("id").asText());o=current(admin,o);assertEquals(200,ship(admin,o,change(o)).getResponse().getStatus());var list=data(call(admin,"GET","/admin/orders?status=PAID&fulfillmentStatus=SHIPPED&pageSize=20",null));assertTrue(list.path("items").size()<=20);for(var item:list.path("items")){assertEquals("PAID",item.path("status").asText());assertEquals("SHIPPED",item.path("fulfillmentStatus").asText());assertFalse(item.has("shipping"));assertFalse(item.has("customerId"));assertFalse(item.has("items"));}for(String q:List.of("page=0","pageSize=21","status=FORGED","fulfillmentStatus=FORGED"))assertEquals(400,call(admin,"GET","/admin/orders?"+q,null).getResponse().getStatus());assertEquals(400,call(admin,"GET","/admin/orders/not-uuid",null).getResponse().getStatus());assertEquals(404,call(admin,"GET","/admin/orders/"+UUID.randomUUID(),null).getResponse().getStatus());assertEquals(257,current(admin,o).path("version").asInt());
    }
    @Test void duplicatePaymentCallbackDuringShipmentCannotResetProgressOrDeductAgain() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=paid(c,f,1);var p=data(call(c,"GET","/customer/orders/"+o.path("id").asText()+"/mock-payments",null)).path("items").get(0);var body=change(o);assertEquals(List.of(200,200),race(()->ship(admin,o,body).getResponse().getStatus(),()->notify(notice(p,"SUCCESS")).getResponse().getStatus()));assertEquals("SHIPPED",current(admin,o).path("fulfillment").path("status").asText());assertEquals(1,ledger(o));assertEquals(4,physical(f));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM order_stock_event WHERE order_id=? AND action='CONSUME'",Integer.class,o.path("id").asText()));
    }
    @Test void databaseConstraintsPreventUnpaidShipmentAndDeliveryBeforeShipment() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var unpaid=place(c,ready(c,f,1));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE customer_order SET fulfillment_status='SHIPPED',shipment_tracking_id=?,shipped_at=CURRENT_TIMESTAMP(6) WHERE id=?","MOCK-"+UUID.randomUUID(),unpaid.path("id").asText()));var o=paid(c,f,1);assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE customer_order SET fulfillment_status='DELIVERED',delivered_at=CURRENT_TIMESTAMP(6) WHERE id=?",o.path("id").asText()));assertEquals(18,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class));
    }
    private List<Integer> race(Callable<Integer> a,Callable<Integer> b) throws Exception{var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var one=pool.submit(()->{start.await();return a.call();});var two=pool.submit(()->{start.await();return b.call();});start.countDown();return List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
}
