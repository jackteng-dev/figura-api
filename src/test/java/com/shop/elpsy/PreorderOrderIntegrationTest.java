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
@SpringBootTest(properties="shop.orders.expiry-delay-ms=600000") @AutoConfigureMockMvc(print=MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named="SHOP_MYSQL_INTEGRATION",matches="true")
class PreorderOrderIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @org.springframework.boot.test.mock.mockito.SpyBean JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired com.shop.elpsy.service.PreorderOrderService service;
    @Autowired com.shop.elpsy.service.NotificationService notifications;
    @Autowired com.shop.elpsy.service.PreorderExpiryJob expiry;
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
        org.mockito.Mockito.reset(jdbc);
        for(String customer:customers){
            for(String order:jdbc.queryForList("SELECT id FROM preorder_order WHERE customer_id=? AND status='PENDING'",String.class,customer)){makeDue(order);service.expire(customer,order);}
            jdbc.update("UPDATE customer SET enabled=FALSE WHERE id=?",customer);
        }
        for(String id:products)jdbc.update("UPDATE catalog_product SET status='ARCHIVED' WHERE id=?",id);
        if(!sessions.isEmpty())redis.delete(sessions);
    }
    private String uuid(){return UUID.randomUUID().toString();}
    private JsonNode batch(Client a,Fixture f,int capacity,int seconds,boolean open)throws Exception{
        var now=java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(8)).withNano(0);
        var body=Map.ofEntries(Map.entry("productId",f.product()),Map.entry("skuId",f.sku()),Map.entry("name","Preorder "+uuid()),Map.entry("description","<script>plain text</script>"),Map.entry("priceTwd",1800),Map.entry("capacity",capacity),Map.entry("deadlineAt",now.plusSeconds(seconds).toString()),Map.entry("arrivalFrom",now.plusDays(30).toLocalDate().toString()),Map.entry("arrivalTo",now.plusDays(40).toLocalDate().toString()),Map.entry("expectedVersion",0),Map.entry("requestId",uuid()),Map.entry("note","Synthetic quota fixture"));
        var r=call(a,"POST","/admin/preorders",body);assertEquals(201,r.getResponse().getStatus());var b=data(r).path("current");return open?closeOrOpen(a,b,"open"):b;
    }
    private JsonNode closeOrOpen(Client a,JsonNode b,String action)throws Exception{var r=call(a,"POST","/admin/preorders/"+b.path("id").asText()+"/"+action,Map.of("expectedVersion",b.path("version").asInt(),"requestId",uuid(),"note","Synthetic batch operation"));assertEquals(201,r.getResponse().getStatus());return data(r).path("current");}
    private JsonNode batchNow(Client a,JsonNode b)throws Exception{return data(call(a,"GET","/admin/preorders/"+b.path("id").asText(),null)).path("batch");}
    private Map<String,Object> ready(Client c,JsonNode b,int qty)throws Exception{String aid=uuid();assertEquals(200,call(c,"PUT","/customer/addresses/"+aid,address(0)).getResponse().getStatus());return new HashMap<>(Map.of("requestId",uuid(),"batchId",b.path("id").asText(),"quantity",qty,"addressId",aid,"addressVersion",1,"expectedSubtotalTwd",1800L*qty));}
    private JsonNode place(Client c,Map<String,Object> body)throws Exception{var r=call(c,"POST","/customer/preorders",body);assertEquals(201,r.getResponse().getStatus(),r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));return data(r).path("order");}
    private String op(JsonNode o){return "/customer/preorders/"+o.path("id").asText();}
    private Map<String,Object> cancelBody(JsonNode o){return new HashMap<>(Map.of("requestId",uuid(),"expectedVersion",o.path("version").asInt()));}
    private int held(JsonNode b){return jdbc.queryForObject("SELECT held_quantity FROM preorder_batch WHERE id=?",Integer.class,b.path("id").asText());}
    private int events(JsonNode o){return jdbc.queryForObject("SELECT COUNT(*) FROM preorder_order_event WHERE order_id=?",Integer.class,o.path("id").asText());}
    private void makeDue(String id){jdbc.update("UPDATE preorder_order SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 29 MINUTE,expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",id);}
    private List<Integer> race(Callable<Integer> a,Callable<Integer> b)throws Exception{var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);try{var x=pool.submit(()->{gate.await();return a.call();});var y=pool.submit(()->{gate.await();return b.call();});gate.countDown();return List.of(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}

    @Test void placementUsesSnapshotsAndNeverPhysicalInventoryOrCart()throws Exception{
        var a=admin();var f=fixture(a,4);var b=batch(a,f,8,86400,true);var c=customer();var stock=jdbc.queryForMap("SELECT * FROM sku_inventory WHERE sku_id=?",f.sku());var before=cart(c);var body=ready(c,b,2);var o=place(c,body);
        assertEquals(3600,o.path("totalTwd").asLong());assertEquals(0,o.path("shippingTwd").asInt());assertEquals("PENDING",o.path("status").asText());assertEquals(2,held(b));assertEquals(stock,jdbc.queryForMap("SELECT * FROM sku_inventory WHERE sku_id=?",f.sku()));assertEquals(before,cart(c));
        var created=java.time.OffsetDateTime.parse(o.path("createdAt").asText());assertEquals(created.plusMinutes(30),java.time.OffsetDateTime.parse(o.path("expiresAt").asText()));assertTrue(o.path("createdAt").asText().endsWith("+08:00"));
        assertFalse(o.has("customerId"));assertFalse(o.has("fingerprint"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM customer_order WHERE id=?",Integer.class,o.path("id").asText()));
    }
    @Test void deadlineEarlierThanThirtyMinutesIsTheHoldLimit()throws Exception{var a=admin();var b=batch(a,fixture(a,0),5,300,true);var c=customer();var o=place(c,ready(c,b,1));assertEquals(o.path("deadlineAt"),o.path("expiresAt"));}
    @Test void replayAfterClosureSourceDeletionAndCancellationNeverReservesAgain()throws Exception{
        var a=admin();var f=fixture(a,0);var b=batch(a,f,5,86400,true);var c=customer();var body=ready(c,b,2);var o=place(c,body);
        closeOrOpen(a,batchNow(a,b),"close");jdbc.update("UPDATE catalog_product SET status='ARCHIVED',title='Renamed' WHERE id=?",f.product());assertEquals(200,call(c,"DELETE","/customer/addresses/"+body.get("addressId")+"?expectedVersion=1",null).getResponse().getStatus());
        var r=call(c,"POST","/customer/preorders",body);assertEquals(200,r.getResponse().getStatus());assertTrue(data(r).path("replayed").asBoolean());assertEquals(o,data(r).path("order"));assertEquals(o,data(call(c,"GET",op(o),null)));
        var cmd=cancelBody(o);assertEquals(200,call(c,"POST",op(o)+"/cancel",cmd).getResponse().getStatus());assertEquals(0,held(b));r=call(c,"POST","/customer/preorders",body);assertEquals("CANCELLED",data(r).path("order").path("status").asText());assertEquals(200,call(c,"POST",op(o)+"/cancel",cmd).getResponse().getStatus());assertEquals(2,events(o));assertEquals(409,call(c,"POST",op(o)+"/cancel",cancelBody(o)).getResponse().getStatus());
    }
    @Test void amountsAddressVersionOwnerAndUnknownFieldsCannotBeSpoofed()throws Exception{
        var a=admin();var b=batch(a,fixture(a,0),5,86400,true);var c=customer();var d=customer();var w=ready(c,b,1);
        var bad=new HashMap<>(w);bad.put("expectedSubtotalTwd",1);assertEquals(409,call(c,"POST","/customer/preorders",bad).getResponse().getStatus());bad=new HashMap<>(w);bad.put("addressVersion",2);assertEquals(409,call(c,"POST","/customer/preorders",bad).getResponse().getStatus());assertEquals(404,call(d,"POST","/customer/preorders",w).getResponse().getStatus());
        for(String key:List.of("customerId","ownerId","unitPriceTwd","shipping","heldQuantity","status")){bad=new HashMap<>(w);bad.put(key,"spoof");assertEquals(400,call(c,"POST","/customer/preorders",bad).getResponse().getStatus());}
        bad=new HashMap<>(w);bad.put("quantity",1.5);assertEquals(400,call(c,"POST","/customer/preorders",bad).getResponse().getStatus());bad.put("quantity",101);assertEquals(400,call(c,"POST","/customer/preorders",bad).getResponse().getStatus());bad.put("quantity",0);assertEquals(400,call(c,"POST","/customer/preorders",bad).getResponse().getStatus());assertEquals(0,held(b));
    }
    @Test void ownOrdersCsrfRealmAndNoStockPaymentFulfillmentEndpoints()throws Exception{
        var a=admin();var b=batch(a,fixture(a,0),5,86400,true);var c=customer();var d=customer();var w=ready(c,b,1);assertEquals(403,call(new Client("bad",c.cookies(),c.id()),"POST","/customer/preorders",w).getResponse().getStatus());assertEquals(401,call(a,"GET","/customer/preorders",null).getResponse().getStatus());assertEquals(401,call(guest(),"GET","/customer/preorders",null).getResponse().getStatus());
        var o=place(c,w);assertEquals(404,call(d,"GET",op(o),null).getResponse().getStatus());assertEquals(404,call(d,"POST",op(o)+"/cancel",cancelBody(o)).getResponse().getStatus());assertEquals(0,data(call(d,"GET","/customer/preorders",null)).path("total").asInt());assertEquals(1,data(call(c,"GET","/customer/preorders",null)).path("total").asInt());assertEquals(404,call(c,"GET","/customer/orders/"+o.path("id").asText(),null).getResponse().getStatus());assertEquals(404,call(c,"GET","/customer/orders/"+o.path("id").asText()+"/mock-payments",null).getResponse().getStatus());assertEquals(404,call(a,"GET","/admin/orders/"+o.path("id").asText(),null).getResponse().getStatus());assertEquals(400,call(c,"GET","/customer/preorders?pageSize=51",null).getResponse().getStatus());assertEquals(400,call(c,"GET","/customer/preorders/bad",null).getResponse().getStatus());
    }
    @Test void inactiveBatchDeadlineProductAndSkuRejectNewOrders()throws Exception{
        var a=admin();var f=fixture(a,0);var b=batch(a,f,5,86400,false);var c=customer();var w=ready(c,b,1);assertEquals(409,call(c,"POST","/customer/preorders",w).getResponse().getStatus());b=closeOrOpen(a,b,"open");
        jdbc.update("UPDATE catalog_product SET status='DRAFT' WHERE id=?",f.product());assertEquals(409,call(c,"POST","/customer/preorders",w).getResponse().getStatus());jdbc.update("UPDATE catalog_product SET status='PUBLISHED' WHERE id=?",f.product());jdbc.update("UPDATE catalog_sku SET enabled=FALSE WHERE id=?",f.sku());assertEquals(409,call(c,"POST","/customer/preorders",w).getResponse().getStatus());jdbc.update("UPDATE catalog_sku SET enabled=TRUE WHERE id=?",f.sku());
        jdbc.update("UPDATE preorder_batch SET deadline_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",b.path("id").asText());assertEquals(409,call(c,"POST","/customer/preorders",w).getResponse().getStatus());assertEquals(0,held(b));
    }
    @Test void concurrentDifferentCustomersCannotOversellLastQuota()throws Exception{var a=admin();var b=batch(a,fixture(a,0),1,86400,true);var c=customer();var d=customer();var x=ready(c,b,1);var y=ready(d,b,1);assertEquals(Set.of(201,409),new HashSet<>(race(()->call(c,"POST","/customer/preorders",x).getResponse().getStatus(),()->call(d,"POST","/customer/preorders",y).getResponse().getStatus())));assertEquals(1,held(b));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM preorder_order WHERE batch_id=?",Integer.class,b.path("id").asText()));}
    @Test void concurrentSameCommandReplaysAndChangedContentConflicts()throws Exception{var a=admin();var b=batch(a,fixture(a,0),5,86400,true);var c=customer();var w=ready(c,b,1);assertEquals(Set.of(201,200),new HashSet<>(race(()->call(c,"POST","/customer/preorders",w).getResponse().getStatus(),()->call(c,"POST","/customer/preorders",w).getResponse().getStatus())));assertEquals(1,held(b));var bad=new HashMap<>(w);bad.put("quantity",2);bad.put("expectedSubtotalTwd",3600);assertEquals(409,call(c,"POST","/customer/preorders",bad).getResponse().getStatus());var other=batch(a,fixture(a,0),5,86400,true);bad=new HashMap<>(w);bad.put("batchId",other.path("id").asText());assertEquals(409,call(c,"POST","/customer/preorders",bad).getResponse().getStatus());}
    @Test void independentBatchesAndSameUuidDifferentOwnersRemainIsolated()throws Exception{var a=admin();var f=fixture(a,0);var b=batch(a,f,1,86400,true);var other=batch(a,f,1,86400,true);var c=customer();var d=customer();var x=ready(c,b,1);var y=ready(d,other,1);y.put("requestId",x.get("requestId"));place(c,x);place(d,y);assertEquals(1,held(b));assertEquals(1,held(other));assertEquals(409,call(c,"POST","/customer/preorders",ready(c,b,1)).getResponse().getStatus());}
    @Test void cancellationAndExpiryRaceReleasesOnceWithDisabledOwnerRecovery()throws Exception{var a=admin();var b=batch(a,fixture(a,0),5,86400,true);var c=customer();var o=place(c,ready(c,b,2));makeDue(o.path("id").asText());var cmd=cancelBody(o);var results=race(()->call(c,"POST",op(o)+"/cancel",cmd).getResponse().getStatus(),()->{service.expire(c.id(),o.path("id").asText());return 200;});assertTrue(results.stream().allMatch(x->x==200||x==409));assertEquals(0,held(b));assertEquals(2,events(o));assertEquals("EXPIRED",data(call(c,"GET",op(o),null)).path("status").asText());
        var next=place(c,ready(c,b,1));makeDue(next.path("id").asText());jdbc.update("UPDATE customer SET enabled=FALSE WHERE id=?",c.id());expiry.run();assertEquals(0,held(b));assertEquals("EXPIRED",jdbc.queryForObject("SELECT status FROM preorder_order WHERE id=?",String.class,next.path("id").asText()));assertEquals(2,events(next));expiry.run();assertEquals(2,events(next));}
    @Test void cancelVersionAndUuidAreStrictAndQuotaReusable()throws Exception{var a=admin();var b=batch(a,fixture(a,0),1,86400,true);var c=customer();var o=place(c,ready(c,b,1));var cmd=cancelBody(o);cmd.put("expectedVersion",2);assertEquals(409,call(c,"POST",op(o)+"/cancel",cmd).getResponse().getStatus());cmd.put("expectedVersion",1);assertEquals(200,call(c,"POST",op(o)+"/cancel",cmd).getResponse().getStatus());cmd.put("expectedVersion",2);assertEquals(409,call(c,"POST",op(o)+"/cancel",cmd).getResponse().getStatus());place(c,ready(c,b,1));assertEquals(1,held(b));}
    @Test void ledgerOrOutboxFailureRollsBackPlacementAndOriginalRetrySucceeds()throws Exception{var a=admin();var b=batch(a,fixture(a,0),5,86400,true);var c=customer();for(String table:List.of("preorder_order_event","notification_outbox")){var count=jdbc.queryForObject("SELECT COUNT(*) FROM preorder_order WHERE batch_id=?",Integer.class,b.path("id").asText());var w=ready(c,b,1);org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("Synthetic write failure")).when(jdbc).update(org.mockito.ArgumentMatchers.startsWith("INSERT INTO "+table),org.mockito.ArgumentMatchers.any(Object[].class));try{assertEquals(500,call(c,"POST","/customer/preorders",w).getResponse().getStatus());assertEquals(0,held(b));assertEquals(count,jdbc.queryForObject("SELECT COUNT(*) FROM preorder_order WHERE batch_id=?",Integer.class,b.path("id").asText()));}finally{org.mockito.Mockito.reset(jdbc);}var o=place(c,w);assertEquals(200,call(c,"POST",op(o)+"/cancel",cancelBody(o)).getResponse().getStatus());}}
    @Test void releaseFailureRollsBackStateLedgerAndQuota()throws Exception{var a=admin();var b=batch(a,fixture(a,0),5,86400,true);var c=customer();var o=place(c,ready(c,b,2));var cmd=cancelBody(o);org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("Synthetic release capture failure")).when(jdbc).update(org.mockito.ArgumentMatchers.startsWith("INSERT INTO notification_outbox"),org.mockito.ArgumentMatchers.any(Object[].class));try{assertEquals(500,call(c,"POST",op(o)+"/cancel",cmd).getResponse().getStatus());assertEquals(2,held(b));assertEquals(o,data(call(c,"GET",op(o),null)));assertEquals(1,events(o));}finally{org.mockito.Mockito.reset(jdbc);}assertEquals(200,call(c,"POST",op(o)+"/cancel",cmd).getResponse().getStatus());assertEquals(0,held(b));}
    @Test void notificationSafeSnapshotsAndParentConstraintSupportExistingQueue()throws Exception{var a=admin();var b=batch(a,fixture(a,0),5,86400,true);var c=customer();var o=place(c,ready(c,b,1));var messages=notifications.list(1,20,null,o.path("id").asText());assertEquals(1,messages.total());var m=messages.items().get(0);assertEquals("PREORDER_PLACED",m.eventKind());assertEquals(o.path("id").asText(),m.orderId());var payload=json.readTree(notifications.detail(m.id()).snapshot());assertEquals(Set.of("orderId","eventKind","orderVersion","orderStatus","fulfillmentStatus","totalTwd"),json.convertValue(payload,Map.class).keySet());assertFalse(payload.toString().contains("0912345678"));assertEquals(200,call(a,"POST","/admin/notifications/"+m.id()+"/mock-dispatch",Map.of("requestId",uuid(),"expectedVersion",m.version(),"note","Synthetic preorder receipt","mode","SUCCESS")).getResponse().getStatus());assertTrue(notifications.detail(m.id()).message().mockAccepted());assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE notification_outbox SET preorder_order_id=NULL WHERE id=?",m.id()));assertEquals(18,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class));}
    @Test void batchClosureRacingPlacementHasValidSerializedOutcome()throws Exception{var a=admin();var b=batch(a,fixture(a,0),5,86400,true);var c=customer();var w=ready(c,b,1);var cmd=Map.of("requestId",uuid(),"expectedVersion",b.path("version").asInt(),"note","Synthetic close race");var results=race(()->call(c,"POST","/customer/preorders",w).getResponse().getStatus(),()->call(a,"POST","/admin/preorders/"+b.path("id").asText()+"/close",cmd).getResponse().getStatus());assertEquals(1,results.stream().filter(x->x==201).count());assertEquals(1,results.stream().filter(x->x==409).count());assertTrue(held(b)==0||held(b)==1);}

    @Test void longTotalDoesNotOverflowAndDatabaseRejectsInvalidQuota()throws Exception{var a=admin();var b=batch(a,fixture(a,0),100,86400,true);jdbc.update("UPDATE preorder_batch SET price_twd=99999999 WHERE id=?",b.path("id").asText());var c=customer();var w=ready(c,b,100);w.put("expectedSubtotalTwd",9999999900L);var o=place(c,w);assertEquals(9999999900L,o.path("totalTwd").asLong());assertEquals(100,held(b));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE preorder_batch SET held_quantity=101 WHERE id=?",b.path("id").asText()));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE preorder_order SET quantity=101 WHERE id=?",o.path("id").asText()));}
    @Test void requestIdCannotBeReusedAcrossPlaceCancelOrAnotherOrder()throws Exception{var a=admin();var b=batch(a,fixture(a,0),5,86400,true);var c=customer();var w=ready(c,b,1);var o=place(c,w);var cmd=cancelBody(o);cmd.put("requestId",w.get("requestId"));assertEquals(409,call(c,"POST",op(o)+"/cancel",cmd).getResponse().getStatus());cmd.put("requestId",uuid());assertEquals(200,call(c,"POST",op(o)+"/cancel",cmd).getResponse().getStatus());var other=place(c,ready(c,b,1));assertEquals(409,call(c,"POST",op(other)+"/cancel",cmd).getResponse().getStatus());assertEquals(1,held(b));}
    @Test void expiryFailureCanRecoverForDisabledCustomerWithoutPartialRelease()throws Exception{var a=admin();var b=batch(a,fixture(a,0),5,86400,true);var c=customer();var o=place(c,ready(c,b,2));makeDue(o.path("id").asText());jdbc.update("UPDATE customer SET enabled=FALSE WHERE id=?",c.id());org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("Synthetic expiry ledger failure")).when(jdbc).update(org.mockito.ArgumentMatchers.startsWith("INSERT INTO preorder_order_event"),org.mockito.ArgumentMatchers.any(Object[].class));try{assertThrows(org.springframework.dao.DataAccessException.class,()->service.expire(c.id(),o.path("id").asText()));assertEquals(2,held(b));assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM preorder_order WHERE id=?",String.class,o.path("id").asText()));assertEquals(1,events(o));}finally{org.mockito.Mockito.reset(jdbc);}expiry.run();assertEquals(0,held(b));assertEquals(2,events(o));assertEquals("SYSTEM",jdbc.queryForObject("SELECT actor_kind FROM preorder_order_event WHERE order_id=? AND action='EXPIRE'",String.class,o.path("id").asText()));}
}
