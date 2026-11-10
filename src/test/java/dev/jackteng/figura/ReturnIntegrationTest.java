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
class ReturnIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
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
        org.mockito.Mockito.reset(refundMapper);org.mockito.Mockito.reset(returnMapper);
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
    @Test void restockReturnPreservesDeliveryAndPaymentAndReturnsStockOnlyAtRefundOnce() throws Exception{
        var a=admin();var f=fixture(a,7);var c=customer();var o=shipped(a,c,f,2);assertEquals(200,deliver(a,o,change(o)).getResponse().getStatus());o=current(a,o);var original=o;int version=jdbc.queryForObject("SELECT version FROM sku_inventory WHERE sku_id=?",Integer.class,f.sku());var apply=application(o);assertEquals(200,requestReturn(c,o,apply).getResponse().getStatus());o=current(a,o);assertEquals(5,o.path("version").asInt());assertEquals("REQUESTED",o.path("returnProgress").path("status").asText());assertEquals(5,physical(f));assertEquals(0,stockEvents(o,"RETURN"));var receipt=receipt(o,"RESTOCK");assertEquals(200,receive(a,o,receipt).getResponse().getStatus());o=current(a,o);assertEquals("RECEIVED",o.path("returnProgress").path("status").asText());assertEquals(5,physical(f));assertEquals(version,jdbc.queryForObject("SELECT version FROM sku_inventory WHERE sku_id=?",Integer.class,f.sku()));var refund=change(o);assertEquals(200,refundReturn(a,o,refund).getResponse().getStatus());var end=current(a,o);assertEquals("REFUNDED",end.path("status").asText());assertEquals(7,end.path("version").asInt());assertEquals("REFUNDED",end.path("returnProgress").path("status").asText());assertEquals(original.path("fulfillment"),end.path("fulfillment"));assertEquals(original.path("shipping"),end.path("shipping"));assertEquals(original.path("paidAt"),end.path("paidAt"));assertEquals(2560,end.path("refund").path("amountTwd").asLong());assertEquals(7,physical(f));assertEquals(0,reserved(f));assertEquals(version+1,jdbc.queryForObject("SELECT version FROM sku_inventory WHERE sku_id=?",Integer.class,f.sku()));assertEquals(1,stockEvents(o,"RETURN"));assertEquals(1,refunds(o));assertEquals(2,returnAudits(o));assertEquals(200,requestReturn(c,original,apply).getResponse().getStatus());assertEquals(200,receive(a,o,receipt).getResponse().getStatus());assertTrue(data(refundReturn(a,o,refund)).path("replayed").asBoolean());assertEquals(7,physical(f));assertEquals(409,refundReturn(a,end,change(end)).getResponse().getStatus());assertEquals(409,deliver(a,end,change(end)).getResponse().getStatus());
        var customer=data(call(c,"GET","/customer/orders/"+o.path("id").asText(),null));assertEquals(end,customer);assertFalse(customer.toString().contains("actorUsername"));assertFalse(customer.toString().contains("合成履約說明"));var pay=data(call(c,"GET","/customer/orders/"+o.path("id").asText()+"/mock-payments",null)).path("items").get(0);assertEquals("SUCCEEDED",pay.path("status").asText());assertEquals("REFUNDED",pay.path("refund").path("status").asText());assertEquals(200,notify(notice(pay,"SUCCESS")).getResponse().getStatus());assertEquals(end,current(a,o));
    }
    @Test void nonRestockInspectionRefundsFullAmountWithoutChangingAnyInventoryOrShipping() throws Exception{
        var a=admin();var f=fixture(a,7);var c=customer();var original=shipped(a,c,f,2);int version=jdbc.queryForObject("SELECT version FROM sku_inventory WHERE sku_id=?",Integer.class,f.sku());var o=received(a,c,original,"NO_RESTOCK");assertEquals(200,refundReturn(a,o,change(o)).getResponse().getStatus());var end=current(a,o);assertEquals("REFUNDED",end.path("status").asText());assertEquals(2560,end.path("refund").path("amountTwd").asLong());assertEquals("NO_RESTOCK",end.path("returnProgress").path("disposition").asText());assertEquals(5,physical(f));assertEquals(version,jdbc.queryForObject("SELECT version FROM sku_inventory WHERE sku_id=?",Integer.class,f.sku()));assertEquals(0,stockEvents(o,"RETURN"));assertEquals(original.path("fulfillment"),end.path("fulfillment"));assertEquals(1,refunds(o));
    }
    @Test void rejectedRequestKeepsStockAndPaymentAndResumesDeliveryButCannotReapplyInDemo() throws Exception{
        var a=admin();var f=fixture(a,7);var c=customer();var o=shipped(a,c,f,1);o=requested(a,c,o);assertEquals(409,deliver(a,o,change(o)).getResponse().getStatus());var input=change(o);assertEquals(200,reject(a,o,input).getResponse().getStatus());var end=current(a,o);assertEquals("REJECTED",end.path("returnProgress").path("status").asText());assertEquals("PAID",end.path("status").asText());assertEquals(6,physical(f));assertEquals(0,refunds(o));assertEquals(1,returnAudits(o));assertEquals(200,reject(a,o,input).getResponse().getStatus());assertEquals(409,requestReturn(c,end,application(end)).getResponse().getStatus());assertEquals(409,refundReturn(a,end,change(end)).getResponse().getStatus());assertEquals(200,deliver(a,end,change(end)).getResponse().getStatus());
    }
    @Test void onlyOwnPaidShippedOrderCanRequestAndRefundRequiresSavedInspection() throws Exception{
        var a=admin();var f=fixture(a,8);var c=customer();var unpaid=place(c,ready(c,f,1));assertEquals(409,requestReturn(c,unpaid,application(unpaid)).getResponse().getStatus());assertEquals(200,cancel(c,unpaid).getResponse().getStatus());var paid=paid(c,f,1);assertEquals(409,requestReturn(c,paid,application(paid)).getResponse().getStatus());var o=shipped(a,c,f,1);var other=customer();assertEquals(404,requestReturn(other,o,application(o)).getResponse().getStatus());assertEquals(404,call(other,"GET","/customer/orders/"+o.path("id").asText()+"/mock-return",null).getResponse().getStatus());assertEquals(409,refundReturn(a,o,change(o)).getResponse().getStatus());assertEquals(404,receive(a,o,receipt(o,"RESTOCK")).getResponse().getStatus());o=requested(a,c,o);assertEquals(409,refundReturn(a,o,change(o)).getResponse().getStatus());assertEquals(409,call(a,"POST",base(o)+"/mock-refund",change(o)).getResponse().getStatus());assertEquals(0,refunds(o));
    }
    @Test void customerRequestReplayRejectsChangedContentAndGlobalCollisionWithoutPartialVersion() throws Exception{
        var a=admin();var f=fixture(a,9);var c=customer();var o=shipped(a,c,f,1);var other=customer();var second=shipped(a,other,f,1);var input=application(o);assertEquals(200,requestReturn(c,o,input).getResponse().getStatus());assertTrue(data(requestReturn(c,o,input)).path("replayed").asBoolean());var bad=new HashMap<>(input);bad.put("reason","different");assertEquals(409,requestReturn(c,o,bad).getResponse().getStatus());bad=new HashMap<>(input);bad.put("expectedOrderVersion",4);assertEquals(409,requestReturn(c,o,bad).getResponse().getStatus());var collision=application(second);collision.put("requestId",input.get("requestId"));assertEquals(409,requestReturn(other,second,collision).getResponse().getStatus());assertEquals(second,current(a,second));assertTrue(current(a,second).path("returnProgress").isNull());
    }
    @Test void identicalAndDifferentCustomerRequestRacesProduceOneApplicationEach() throws Exception{
        var a=admin();var f=fixture(a,9);var c=customer();var o=shipped(a,c,f,1);var input=application(o);assertEquals(List.of(200,200),race(()->requestReturn(c,o,input).getResponse().getStatus(),()->requestReturn(c,o,input).getResponse().getStatus()));assertEquals(4,current(a,o).path("version").asInt());var next=shipped(a,c,f,1);var one=application(next);var two=application(next);assertEquals(Set.of(200,409),new HashSet<>(race(()->requestReturn(c,next,one).getResponse().getStatus(),()->requestReturn(c,next,two).getResponse().getStatus())));assertEquals(4,current(a,next).path("version").asInt());assertEquals(7,physical(f));
    }
    @Test void inspectionReplaysAndConcurrentRejectOrDifferentDispositionHaveOneWinner() throws Exception{
        var a=admin();var f=fixture(a,9);var c=customer();var o=requested(a,c,shipped(a,c,f,1));var input=receipt(o,"RESTOCK");assertEquals(List.of(200,200),race(()->receive(a,o,input).getResponse().getStatus(),()->receive(a,o,input).getResponse().getStatus()));assertEquals(1,returnAudits(o));var changed=new HashMap<>(input);changed.put("disposition","NO_RESTOCK");assertEquals(409,receive(a,o,changed).getResponse().getStatus());var next=requested(a,c,shipped(a,c,f,1));var one=receipt(next,"RESTOCK");var two=change(next);assertEquals(Set.of(200,409),new HashSet<>(race(()->receive(a,next,one).getResponse().getStatus(),()->reject(a,next,two).getResponse().getStatus())));assertEquals(1,returnAudits(next));assertEquals(7,physical(f));
    }
    @Test void identicalRefundRaceRestocksOnceAndCrossOrderGlobalCollisionRollsBackLoser() throws Exception{
        var a=admin();var f=fixture(a,9);var c=customer();var o=received(a,c,shipped(a,c,f,1),"RESTOCK");var input=change(o);assertEquals(List.of(200,200),race(()->refundReturn(a,o,input).getResponse().getStatus(),()->refundReturn(a,o,input).getResponse().getStatus()));assertEquals(9,physical(f));assertEquals(1,refunds(o));var one=received(a,c,shipped(a,c,f,1),"RESTOCK");var otherCustomer=customer();var two=received(a,otherCustomer,shipped(a,otherCustomer,f,1),"RESTOCK");var other=employee(true);var first=change(one);var second=change(two);second.put("requestId",first.get("requestId"));assertEquals(Set.of(200,409),new HashSet<>(race(()->refundReturn(a,one,first).getResponse().getStatus(),()->refundReturn(other,two,second).getResponse().getStatus())));assertEquals(1,refunds(one)+refunds(two));assertEquals(8,physical(f));assertEquals(1,stockEvents(one,"RETURN")+stockEvents(two,"RETURN"));
    }
    @Test void applicationAndInspectionLedgerFailuresRollbackAndSameIdsCanRetry() throws Exception{
        var a=admin();var f=fixture(a,7);var c=customer();var original=shipped(a,c,f,1);var application=application(original);org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic application failure")).when(returnMapper).insert(org.mockito.ArgumentMatchers.any());assertEquals(500,requestReturn(c,original,application).getResponse().getStatus());org.mockito.Mockito.reset(returnMapper);assertEquals(original,current(a,original));assertEquals(200,requestReturn(c,original,application).getResponse().getStatus());var o=current(a,original);var input=receipt(o,"RESTOCK");org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic inspection audit failure")).when(returnMapper).append(org.mockito.ArgumentMatchers.any());assertEquals(500,receive(a,o,input).getResponse().getStatus());org.mockito.Mockito.reset(returnMapper);assertEquals(o,current(a,o));assertEquals(0,returnAudits(o));assertEquals(200,receive(a,o,input).getResponse().getStatus());assertEquals(6,physical(f));
    }
    @Test void bothRefundLedgersAreAtomicWithStockOrderAndReturnState() throws Exception{
        var a=admin();var f=fixture(a,7);var c=customer();var o=received(a,c,shipped(a,c,f,2),"RESTOCK");var input=change(o);org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic financial audit failure")).when(refundMapper).append(org.mockito.ArgumentMatchers.any());assertEquals(500,refundReturn(a,o,input).getResponse().getStatus());org.mockito.Mockito.reset(refundMapper);assertEquals(o,current(a,o));assertEquals(5,physical(f));assertEquals(0,refunds(o));org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic return refund audit failure")).when(returnMapper).append(org.mockito.ArgumentMatchers.any());assertEquals(500,refundReturn(a,o,input).getResponse().getStatus());org.mockito.Mockito.reset(returnMapper);assertEquals(o,current(a,o));assertEquals(0,refunds(o));assertEquals(0,stockEvents(o,"RETURN"));assertEquals(1,returnAudits(o));assertEquals(5,physical(f));assertEquals(200,refundReturn(a,o,input).getResponse().getStatus());assertEquals(7,physical(f));
    }
    @Test void pendingReviewAllowsInspectionButFreezesReturnRefundUntilMockReviewResolution() throws Exception{
        var a=admin();var f=fixture(a,7);var c=customer();var o=place(c,ready(c,f,2));var first=intent(c,o);notify(notice(first,"FAILURE"));var primary=intent(c,o);notify(notice(primary,"SUCCESS"));o=current(a,o);assertEquals(200,ship(a,o,change(o)).getResponse().getStatus());o=requested(a,c,current(a,o));notify(notice(first,"SUCCESS"));assertEquals(200,receive(a,o,receipt(o,"RESTOCK")).getResponse().getStatus());o=current(a,o);assertEquals(409,refundReturn(a,o,change(o)).getResponse().getStatus());assertEquals(200,call(a,"POST",base(o)+"/mock-review-refund/"+first.path("id").asText(),change(o)).getResponse().getStatus());o=current(a,o);assertEquals(200,refundReturn(a,o,change(o)).getResponse().getStatus());assertEquals(7,physical(f));assertEquals(2,refunds(o));assertEquals(1,stockEvents(o,"RETURN"));
    }
    @Test void liveActorAuthorizationRealmCsrfUnknownFieldsAndAuditWritesAreGuarded() throws Exception{
        var a=admin();var f=fixture(a,7);var c=customer();var o=shipped(a,c,f,1);var request=application(o);assertEquals(401,call(a,"POST","/customer/orders/"+o.path("id").asText()+"/mock-return",request).getResponse().getStatus());assertEquals(403,requestReturn(new Client("wrong",c.cookies(),c.id()),o,request).getResponse().getStatus());for(String field:List.of("owner","amountTwd","disposition","actorId")){var bad=new HashMap<>(request);bad.put(field,"forged");assertEquals(400,requestReturn(c,o,bad).getResponse().getStatus());}assertEquals(200,requestReturn(c,o,request).getResponse().getStatus());o=current(a,o);var input=receipt(o,"RESTOCK");var staff=staff();assertEquals(200,call(staff,"GET",base(o)+"/returns",null).getResponse().getStatus());assertEquals(401,call(c,"GET",base(o)+"/returns",null).getResponse().getStatus());assertEquals(403,receive(staff,o,input).getResponse().getStatus());assertEquals(403,reject(staff,o,change(o)).getResponse().getStatus());assertEquals(403,refundReturn(staff,o,change(o)).getResponse().getStatus());var bad=new HashMap<>(input);bad.put("disposition","FORGED");assertEquals(400,receive(a,o,bad).getResponse().getStatus());bad=new HashMap<>(input);bad.put("expectedOrderVersion",4.5);assertEquals(400,receive(a,o,bad).getResponse().getStatus());assertEquals(403,call(a,"DELETE",base(o)+"/returns",null).getResponse().getStatus());assertEquals(403,call(c,"DELETE","/customer/orders/"+o.path("id").asText()+"/mock-return",null).getResponse().getStatus());
        var manager=employee(true);int id=Integer.parseInt(manager.id());var principal=new dev.jackteng.figura.pojo.CustomUserDetails("forged",id,List.of());String orderId=o.path("id").asText();var payload=new dev.jackteng.figura.dto.FulfillmentPayloads.Change(UUID.randomUUID().toString(),o.path("version").asInt(),"合成說明");jdbc.update("DELETE FROM user_role WHERE user_id=?",id);jdbc.update("INSERT INTO user_role VALUES(?,2)",id);assertEquals(403,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->returnService.change(orderId,"RECEIVE",payload,"RESTOCK",principal)).getStatusCode().value());jdbc.update("UPDATE user SET is_stop='T' WHERE id=?",id);assertEquals(401,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->returnService.change(orderId,"RECEIVE",payload,"RESTOCK",principal)).getStatusCode().value());assertEquals(0,returnAudits(o));
    }
    @Test void multiSkuOverflowRollsBackEarlierRestockAndRetriesAfterAuditedCorrection() throws Exception{
        var a=admin();var first=fixture(a,7);var second=fixture(a,7);var c=customer();var body=ready(c,first,2);assertEquals(200,call(c,"PUT",second.cart(),quantity(cart(c).path("version").asInt(),2)).getResponse().getStatus());body.put("cartVersion",cart(c).path("version").asInt());body.put("expectedSubtotalTwd",5120L);var o=place(c,body);notify(notice(intent(c,o),"SUCCESS"));o=current(a,o);assertEquals(200,ship(a,o,change(o)).getResponse().getStatus());o=received(a,c,current(a,o),"RESTOCK");var overflow=first.product().compareTo(second.product())>0?first:second;String path="/admin/products/"+overflow.product()+"/skus/"+overflow.sku()+"/inventory/adjustments";int version=jdbc.queryForObject("SELECT version FROM sku_inventory WHERE sku_id=?",Integer.class,overflow.sku());assertEquals(201,call(a,"POST",path,Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",version,"delta",999995,"reason","RESTOCK","note","合成容量測試")).getResponse().getStatus());var input=change(o);assertEquals(409,refundReturn(a,o,input).getResponse().getStatus());assertEquals(o,current(a,o));assertEquals(0,refunds(o));assertEquals(0,stockEvents(o,"RETURN"));assertEquals(5,physical(overflow==first?second:first));assertEquals(1000000,physical(overflow));version=jdbc.queryForObject("SELECT version FROM sku_inventory WHERE sku_id=?",Integer.class,overflow.sku());assertEquals(201,call(a,"POST",path,Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",version,"delta",-2,"reason","CORRECTION","note","合成容量修正")).getResponse().getStatus());assertEquals(200,refundReturn(a,o,input).getResponse().getStatus());assertEquals(2,stockEvents(o,"RETURN"));assertEquals(7,physical(overflow==first?second:first));assertEquals(1000000,physical(overflow));
    }
    @Test void archivedProductAndDisabledCustomerDoNotBlockSavedReturnAndDatabaseRejectsMissingDisposition() throws Exception{
        var a=admin();var f=fixture(a,7);var c=customer();var o=requested(a,c,shipped(a,c,f,1));jdbc.update("UPDATE catalog_product SET status='ARCHIVED' WHERE id=?",f.product());jdbc.update("UPDATE customer SET enabled=FALSE WHERE id=?",c.id());String savedOrderId=o.path("id").asText();assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE mock_order_return SET status='RECEIVED',received_at=CURRENT_TIMESTAMP(6) WHERE order_id=?",savedOrderId));assertEquals(200,receive(a,o,receipt(o,"RESTOCK")).getResponse().getStatus());o=current(a,o);assertEquals(200,refundReturn(a,o,change(o)).getResponse().getStatus());assertEquals(7,physical(f));assertEquals(o.path("shipping"),current(a,o).path("shipping"));assertEquals(18,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class));
    }
    private List<Integer> race(Callable<Integer> a,Callable<Integer> b) throws Exception{var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var one=pool.submit(()->{start.await();return a.call();});var two=pool.submit(()->{start.await();return b.call();});start.countDown();return List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
}
