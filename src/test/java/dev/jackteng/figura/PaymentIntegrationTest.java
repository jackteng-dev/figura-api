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
class PaymentIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired dev.jackteng.figura.service.OrderService orderService;
    @Autowired dev.jackteng.figura.service.OrderExpiryJob expiry;
    @Autowired dev.jackteng.figura.service.MockPaymentGateway gateway;
    @org.springframework.boot.test.mock.mockito.SpyBean dev.jackteng.figura.mapper.PaymentMapper paymentMapper;
    @org.springframework.boot.test.mock.mockito.SpyBean dev.jackteng.figura.mapper.OrderMapper orderMapper;
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
        org.mockito.Mockito.reset(orderMapper,paymentMapper);
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
    @Test void signedSuccessConsumesExactlyOnceAndPaidOrderCannotCancelOrExpire() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=place(c,ready(c,f,2));var p=intent(c,o);var body=notice(p,"SUCCESS");
        var r=notify(body);assertEquals(200,r.getResponse().getStatus());assertEquals("APPLIED",data(r).path("disposition").asText());assertFalse(data(r).has("order"));assertFalse(data(r).has("shipping"));
        assertEquals(3,physical(f));assertEquals(0,reserved(f));var paid=data(call(c,"GET","/customer/orders/"+o.path("id").asText(),null));assertEquals("PAID",paid.path("status").asText());assertFalse(paid.path("paidAt").isNull());assertEquals(409,cancel(c,paid).getResponse().getStatus());due(paid);orderService.expire(c.id(),paid.path("id").asText());assertEquals(3,physical(f));
        assertTrue(data(notify(body)).path("replayed").asBoolean());body.put("eventId",UUID.randomUUID().toString());assertEquals("ALREADY_SUCCEEDED",data(notify(body)).path("disposition").asText());body.put("eventId",UUID.randomUUID().toString());body.put("outcome","FAILURE");assertEquals("ALREADY_SUCCEEDED",data(notify(body)).path("disposition").asText());assertEquals(3,physical(f));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM order_stock_event WHERE order_id=? AND action='CONSUME'",Integer.class,o.path("id").asText()));
    }
    @Test void failedAttemptRetainsHoldAndNewIntentCanSettle() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=place(c,ready(c,f,2));var p=intent(c,o);var body=notice(p,"FAILURE");assertEquals("PAYMENT_FAILED",data(notify(body)).path("disposition").asText());assertEquals(5,physical(f));assertEquals(2,reserved(f));
        var settled=jdbc.queryForObject("SELECT settled_at FROM mock_payment WHERE id=?",java.time.LocalDateTime.class,p.path("id").asText());body.put("eventId",UUID.randomUUID().toString());assertEquals("ALREADY_FAILED",data(notify(body)).path("disposition").asText());assertEquals(settled,jdbc.queryForObject("SELECT settled_at FROM mock_payment WHERE id=?",java.time.LocalDateTime.class,p.path("id").asText()));
        var next=intent(c,o);assertNotEquals(p.path("id").asText(),next.path("id").asText());assertEquals("APPLIED",data(notify(notice(next,"SUCCESS"))).path("disposition").asText());assertEquals(3,physical(f));assertEquals(0,reserved(f));
    }
    @Test void invalidSignatureTimestampAmountCurrencyTransactionAndPayloadNeverWrite() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=place(c,ready(c,f,2));var p=intent(c,o);var body=notice(p,"SUCCESS");String ts=Long.toString(java.time.Instant.now().getEpochSecond());
        assertEquals(401,signed(json.writeValueAsString(body),ts,"0".repeat(64)).getResponse().getStatus());assertEquals(401,signed(json.writeValueAsString(body),"1000000000",null).getResponse().getStatus());
        for(String field:List.of("amountTwd","transactionId","currency","customerId")){var bad=new HashMap<>(body);bad.put(field,field.equals("amountTwd")?1:field.equals("currency")?"USD":"forged");int code=notify(bad).getResponse().getStatus();assertEquals(field.equals("amountTwd")?409:400,code);}
        var bad=new HashMap<>(body);bad.put("transactionId","mock_"+UUID.randomUUID());assertEquals(409,notify(bad).getResponse().getStatus());bad=new HashMap<>(body);bad.put("amountTwd",1.5);assertEquals(400,notify(bad).getResponse().getStatus());assertEquals(413,signed(" ".repeat(8193),ts,null).getResponse().getStatus());
        assertEquals(5,physical(f));assertEquals(2,reserved(f));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mock_payment_event WHERE payment_id=?",Integer.class,p.path("id").asText()));
    }
    @Test void customerOwnershipCookieRealmCsrfAndUnknownFieldsAreProtected() throws Exception{
        var admin=admin();var f=fixture(admin,5);var a=customer();var b=customer();var o=place(a,ready(a,f,2));String path="/customer/orders/"+o.path("id").asText()+"/mock-payments";var body=new HashMap<String,Object>(Map.of("requestId",UUID.randomUUID().toString(),"expectedOrderVersion",1));
        assertEquals(401,call(admin,"GET",path,null).getResponse().getStatus());assertEquals(401,call(guest(),"POST",path,body).getResponse().getStatus());assertEquals(404,call(b,"GET",path,null).getResponse().getStatus());assertEquals(404,call(b,"POST",path,body).getResponse().getStatus());assertEquals(403,call(new Client("wrong",a.cookies(),a.id()),"POST",path,body).getResponse().getStatus());
        var bad=new HashMap<>(body);bad.put("amountTwd",1);assertEquals(400,call(a,"POST",path,bad).getResponse().getStatus());var p=intent(a,o);String simulate="/customer/mock-payments/"+p.path("id").asText()+"/simulate";var sim=Map.of("eventId",UUID.randomUUID().toString(),"outcome","SUCCESS");
        assertEquals(404,call(b,"POST",simulate,sim).getResponse().getStatus());assertEquals(403,call(new Client("wrong",a.cookies(),a.id()),"POST",simulate,sim).getResponse().getStatus());assertEquals(400,call(a,"POST",simulate,Map.of("eventId",UUID.randomUUID().toString(),"outcome","SUCCESS","amountTwd",1)).getResponse().getStatus());assertEquals(403,call(a,"GET",simulate,null).getResponse().getStatus());assertEquals(5,physical(f));jdbc.update("UPDATE customer SET enabled=FALSE WHERE id=?",a.id());assertEquals(401,call(a,"POST",simulate,sim).getResponse().getStatus());assertEquals(200,notify(notice(p,"SUCCESS")).getResponse().getStatus());assertEquals(3,physical(f));
    }
    @Test void concurrentIntentReplayIsOneAndDifferentOperationCannotCreateSecondActiveAttempt() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=place(c,ready(c,f,2));String path="/customer/orders/"+o.path("id").asText()+"/mock-payments";var body=Map.of("requestId",UUID.randomUUID().toString(),"expectedOrderVersion",1);
        assertEquals(List.of(200,200),race(()->call(c,"POST",path,body).getResponse().getStatus(),()->call(c,"POST",path,body).getResponse().getStatus()));assertEquals(1,data(call(c,"GET",path,null)).path("items").size());assertEquals(409,call(c,"POST",path,Map.of("requestId",UUID.randomUUID().toString(),"expectedOrderVersion",1)).getResponse().getStatus());assertEquals(409,call(c,"POST",path,Map.of("requestId",body.get("requestId"),"expectedOrderVersion",2)).getResponse().getStatus());
        var p=data(call(c,"GET",path,null)).path("items").get(0);assertEquals(200,notify(notice(p,"SUCCESS")).getResponse().getStatus());var replay=call(c,"POST",path,body);assertTrue(data(replay).path("replayed").asBoolean());assertEquals("SUCCEEDED",data(replay).path("payment").path("status").asText());
    }
    @Test void concurrentSameCallbackAndEventIdContentMismatchCannotDoubleConsume() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=place(c,ready(c,f,2));var p=intent(c,o);var body=notice(p,"SUCCESS");assertEquals(List.of(200,200),race(()->notify(body).getResponse().getStatus(),()->notify(body).getResponse().getStatus()));assertEquals(3,physical(f));body.put("outcome","FAILURE");assertEquals(409,notify(body).getResponse().getStatus());assertEquals(3,physical(f));
    }
    @Test void globalEventCollisionRollsBackOtherOrderSettlement() throws Exception{
        var admin=admin();var one=fixture(admin,3);var two=fixture(admin,3);var a=customer();var b=customer();var oa=place(a,ready(a,one,1));var ob=place(b,ready(b,two,1));var pa=intent(a,oa);var pb=intent(b,ob);var first=notice(pa,"SUCCESS");var second=notice(pb,"SUCCESS");second.put("eventId",first.get("eventId"));
        assertEquals(Set.of(200,409),new HashSet<>(race(()->notify(first).getResponse().getStatus(),()->notify(second).getResponse().getStatus())));assertEquals(5,physical(one)+physical(two));assertEquals(1,reserved(one)+reserved(two));
    }
    @Test void paymentAndCancellationRaceNeverBothConsumeAndRelease() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=place(c,ready(c,f,2));var p=intent(c,o);var body=notice(p,"SUCCESS");var codes=race(()->notify(body).getResponse().getStatus(),()->cancel(c,o).getResponse().getStatus());assertEquals(200,codes.get(0));assertTrue(Set.of(200,409).contains(codes.get(1)));assertEquals(0,reserved(f));
        String state=data(call(c,"GET","/customer/orders/"+o.path("id").asText(),null)).path("status").asText();assertTrue(Set.of("PAID","CANCELLED").contains(state));assertEquals(state.equals("PAID")?3:5,physical(f));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM order_stock_event WHERE order_id=? AND action IN ('CONSUME','RELEASE')",Integer.class,o.path("id").asText()));
    }
    @Test void expiredOrCancelledLateSuccessIsReviewAndNeverRestoresStock() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=place(c,ready(c,f,2));var p=intent(c,o);due(o);var body=notice(p,"SUCCESS");assertEquals(List.of(200,200),race(()->notify(body).getResponse().getStatus(),()->{orderService.expire(c.id(),o.path("id").asText());return 200;}));assertEquals("REVIEW_REQUIRED",jdbc.queryForObject("SELECT status FROM mock_payment WHERE id=?",String.class,p.path("id").asText()));assertEquals(5,physical(f));assertEquals(0,reserved(f));
        var next=place(c,ready(c,f,1));var second=intent(c,next);assertEquals(200,cancel(c,next).getResponse().getStatus());assertEquals("REVIEW_REQUIRED",data(notify(notice(second,"SUCCESS"))).path("disposition").asText());assertEquals(5,physical(f));assertEquals(0,reserved(f));
    }
    @Test void contradictorySuccessAfterFailureIsReviewAndStopsNewAttempts() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=place(c,ready(c,f,2));var p=intent(c,o);assertEquals(200,notify(notice(p,"FAILURE")).getResponse().getStatus());var active=intent(c,o);assertEquals("REVIEW_REQUIRED",data(notify(notice(p,"SUCCESS"))).path("disposition").asText());assertEquals(5,physical(f));assertEquals(2,reserved(f));var options=data(call(c,"GET","/customer/orders/"+o.path("id").asText()+"/mock-payments",null));assertFalse(options.path("canStart").asBoolean());assertEquals("REVIEW_REQUIRED",data(notify(notice(active,"SUCCESS"))).path("disposition").asText());assertEquals(5,physical(f));assertEquals(2,reserved(f));
    }
    @Test void consumeLedgerFailureRollsBackAndSameNotificationCanRetry() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=place(c,ready(c,f,2));var p=intent(c,o);var body=notice(p,"SUCCESS");
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic consume ledger failure")).when(orderMapper).event(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq("CONSUME"),org.mockito.ArgumentMatchers.anyInt());assertEquals(500,notify(body).getResponse().getStatus());org.mockito.Mockito.reset(orderMapper);
        assertEquals(5,physical(f));assertEquals(2,reserved(f));assertEquals("INITIATED",jdbc.queryForObject("SELECT status FROM mock_payment WHERE id=?",String.class,p.path("id").asText()));assertNull(paymentMapper.event(body.get("eventId").toString()));assertEquals(200,notify(body).getResponse().getStatus());assertEquals(3,physical(f));
    }
    @Test void multiSkuSettlementFailureCannotLeavePartialPhysicalDeduction() throws Exception{
        var admin=admin();var first=fixture(admin,5);var second=fixture(admin,5);var c=customer();var body=ready(c,first,1);assertEquals(200,call(c,"PUT",second.cart(),quantity(1,1)).getResponse().getStatus());body.put("cartVersion",2);body.put("expectedSubtotalTwd",2560L);var o=place(c,body);var p=intent(c,o);var event=notice(p,"SUCCESS");
        String last=first.product().compareTo(second.product())>0?first.sku():second.sku();org.mockito.Mockito.doReturn(0).when(paymentMapper).consume(org.mockito.ArgumentMatchers.eq(last),org.mockito.ArgumentMatchers.anyInt());assertEquals(500,notify(event).getResponse().getStatus());org.mockito.Mockito.reset(paymentMapper);
        assertEquals(5,physical(first));assertEquals(5,physical(second));assertEquals(1,reserved(first));assertEquals(1,reserved(second));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM order_stock_event WHERE order_id=? AND action='CONSUME'",Integer.class,o.path("id").asText()));assertEquals(200,notify(event).getResponse().getStatus());assertEquals(4,physical(first));assertEquals(4,physical(second));
    }
    @Test void notificationLedgerFailureRollsBackPaidStateStockAndPayment() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=place(c,ready(c,f,2));var p=intent(c,o);var body=notice(p,"SUCCESS");
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic notification ledger unavailable")).when(paymentMapper).append(org.mockito.ArgumentMatchers.any());assertEquals(500,notify(body).getResponse().getStatus());org.mockito.Mockito.reset(paymentMapper);
        assertEquals(5,physical(f));assertEquals(2,reserved(f));assertEquals("PENDING_PAYMENT",data(call(c,"GET","/customer/orders/"+o.path("id").asText(),null)).path("status").asText());assertNull(paymentMapper.event(body.get("eventId").toString()));assertEquals(200,notify(body).getResponse().getStatus());assertEquals(3,physical(f));
    }
    private List<Integer> race(Callable<Integer> a,Callable<Integer> b) throws Exception{var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var one=pool.submit(()->{start.await();return a.call();});var two=pool.submit(()->{start.await();return b.call();});start.countDown();return List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
}
