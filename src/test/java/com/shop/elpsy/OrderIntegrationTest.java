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
class OrderIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired com.shop.elpsy.service.OrderService orderService;
    @Autowired com.shop.elpsy.service.OrderExpiryJob expiry;
    @org.springframework.boot.test.mock.mockito.SpyBean com.shop.elpsy.mapper.OrderMapper orderMapper;
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
        org.mockito.Mockito.reset(orderMapper);
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
    @Test void snapshotsReservationsAndReplaySurviveSourceEditsAndCancellation() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var body=ready(c,f,2);var order=place(c,body);String id=order.path("id").asText();
        assertEquals(2560,order.path("totalTwd").asLong());assertEquals(0,order.path("shippingTwd").asInt());assertEquals(2,reserved(f));assertEquals(5,jdbc.queryForObject("SELECT quantity FROM sku_inventory WHERE sku_id=?",Integer.class,f.sku()));assertEquals(0,cart(c).path("items").size());assertEquals(2,cart(c).path("version").asInt());
        jdbc.update("UPDATE catalog_product SET status='DRAFT',title='Changed private draft' WHERE id=?",f.product());jdbc.update("UPDATE catalog_sku SET price_twd=9900,label='Changed option' WHERE id=?",f.sku());
        assertEquals(200,call(c,"DELETE","/customer/addresses/"+body.get("addressId")+"?expectedVersion=1",null).getResponse().getStatus());
        assertEquals(order,data(call(c,"GET","/customer/orders/"+id,null)));var replay=call(c,"POST","/customer/orders",body);assertTrue(data(replay).path("replayed").asBoolean());assertEquals(id,data(replay).path("order").path("id").asText());assertEquals(2,reserved(f));
        assertEquals(200,cancel(c,order).getResponse().getStatus());assertEquals(0,reserved(f));assertEquals("CANCELLED",place(c,body).path("status").asText());assertEquals(0,reserved(f));assertEquals(200,cancel(c,order).getResponse().getStatus());assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM order_stock_event WHERE order_id=?",Integer.class,id));
    }
    @Test void customerOwnershipCsrfRealmAndInputAreEnforced() throws Exception{
        var admin=admin();var f=fixture(admin,3);var a=customer();var b=customer();var body=ready(a,f,1);var order=place(a,body);String path="/customer/orders/"+order.path("id").asText();
        assertEquals(404,call(b,"GET",path,null).getResponse().getStatus());assertEquals(404,call(b,"POST",path+"/cancel",Map.of("expectedVersion",1)).getResponse().getStatus());assertEquals(0,data(call(b,"GET","/customer/orders?customerId="+a.id(),null)).path("items").size());
        assertEquals(401,call(admin,"GET","/customer/orders",null).getResponse().getStatus());assertEquals(401,call(guest(),"GET",path,null).getResponse().getStatus());assertEquals(403,call(new Client("bad",a.cookies(),a.id()),"POST","/customer/orders",body).getResponse().getStatus());
        for(String field:List.of("customerId","shippingTwd","priceTwd","items","status")){var bad=new HashMap<>(body);bad.put(field,"injected");assertEquals(400,call(a,"POST","/customer/orders",bad).getResponse().getStatus());}
        var bad=new HashMap<>(body);bad.put("expectedSubtotalTwd",1.5);assertEquals(400,call(a,"POST","/customer/orders",bad).getResponse().getStatus());
        assertEquals(400,call(a,"GET","/customer/orders/bad",null).getResponse().getStatus());assertEquals(400,call(a,"GET","/customer/orders?pageSize=21",null).getResponse().getStatus());assertEquals(403,call(a,"DELETE",path,null).getResponse().getStatus());
        assertEquals(1,reserved(f));
    }
    @Test void changedCartAddressPriceAndUnpublishedSkuNeverCreateOrders() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var body=ready(c,f,2);
        var wrong=new HashMap<>(body);wrong.put("cartVersion",0);assertEquals(409,call(c,"POST","/customer/orders",wrong).getResponse().getStatus());wrong=new HashMap<>(body);wrong.put("addressVersion",2);assertEquals(409,call(c,"POST","/customer/orders",wrong).getResponse().getStatus());wrong=new HashMap<>(body);wrong.put("addressId",UUID.randomUUID().toString());assertEquals(404,call(c,"POST","/customer/orders",wrong).getResponse().getStatus());
        jdbc.update("UPDATE catalog_sku SET price_twd=1300 WHERE id=?",f.sku());assertEquals(409,call(c,"POST","/customer/orders",body).getResponse().getStatus());body.put("expectedSubtotalTwd",2600L);
        jdbc.update("UPDATE catalog_product SET status='DRAFT' WHERE id=?",f.product());assertEquals(409,call(c,"POST","/customer/orders",body).getResponse().getStatus());jdbc.update("UPDATE catalog_product SET status='PUBLISHED' WHERE id=?",f.product());jdbc.update("UPDATE catalog_sku SET enabled=FALSE WHERE id=?",f.sku());assertEquals(409,call(c,"POST","/customer/orders",body).getResponse().getStatus());
        assertEquals(0,reserved(f));assertEquals(1,cart(c).path("version").asInt());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM customer_order WHERE customer_id=?",Integer.class,c.id()));
    }
    @Test void concurrentSameOperationOnlyReservesOnceAndMismatchedReplayIsConflict() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var body=ready(c,f,2);
        assertEquals(List.of(200,200),race(()->call(c,"POST","/customer/orders",body).getResponse().getStatus(),()->call(c,"POST","/customer/orders",body).getResponse().getStatus()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM customer_order WHERE customer_id=?",Integer.class,c.id()));assertEquals(2,reserved(f));var changed=new HashMap<>(body);changed.put("expectedSubtotalTwd",999L);assertEquals(409,call(c,"POST","/customer/orders",changed).getResponse().getStatus());
        changed=new HashMap<>(body);changed.put("requestId",UUID.randomUUID().toString());assertEquals(409,call(c,"POST","/customer/orders",changed).getResponse().getStatus());assertEquals(2,reserved(f));
    }
    @Test void finalUnitRaceCannotOversellAndCatalogCartUseAvailableStock() throws Exception{
        var admin=admin();var f=fixture(admin,1);var a=customer();var b=customer();var one=ready(a,f,1);var two=ready(b,f,1);
        assertEquals(Set.of(200,409),new HashSet<>(race(()->call(a,"POST","/customer/orders",one).getResponse().getStatus(),()->call(b,"POST","/customer/orders",two).getResponse().getStatus())));
        assertEquals(1,reserved(f));var loser=cart(a).path("items").size()==0?b:a;assertEquals("SOLD_OUT",cart(loser).path("items").get(0).path("issue").asText());assertFalse(data(call(guest(),"GET","/catalog/products/"+f.product(),null)).path("skus").get(0).path("available").asBoolean());
        assertEquals(409,call(admin,"POST","/admin/products/"+f.product()+"/skus/"+f.sku()+"/inventory/adjustments",Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",2,"delta",-1,"reason","DAMAGE","note","Cannot damage held stock")).getResponse().getStatus());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE sku_inventory SET quantity=0 WHERE sku_id=?",f.sku()));
    }
    @Test void multiProductDeadlockOrderAndInsufficientSecondLineAreAtomic() throws Exception{
        var admin=admin();var first=fixture(admin,1);var second=fixture(admin,1);var a=customer();var b=customer();var one=ready(a,first,1);var two=ready(b,second,1);
        assertEquals(200,call(a,"PUT",second.cart(),quantity(1,1)).getResponse().getStatus());assertEquals(200,call(b,"PUT",first.cart(),quantity(1,1)).getResponse().getStatus());one.put("cartVersion",2);two.put("cartVersion",2);one.put("expectedSubtotalTwd",2560L);two.put("expectedSubtotalTwd",2560L);
        assertEquals(Set.of(200,409),new HashSet<>(race(()->call(a,"POST","/customer/orders",one).getResponse().getStatus(),()->call(b,"POST","/customer/orders",two).getResponse().getStatus())));assertEquals(1,reserved(first));assertEquals(1,reserved(second));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM customer_order WHERE customer_id IN (?,?)",Integer.class,a.id(),b.id()));
    }
    @Test void ledgerFailureRollsBackReservationOrderAndCart() throws Exception{
        var admin=admin();var f=fixture(admin,3);var c=customer();var body=ready(c,f,2);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic ledger unavailable")).when(orderMapper).event(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq("RESERVE"),org.mockito.ArgumentMatchers.anyInt());
        assertEquals(500,call(c,"POST","/customer/orders",body).getResponse().getStatus());org.mockito.Mockito.reset(orderMapper);
        assertEquals(0,reserved(f));assertEquals(1,cart(c).path("version").asInt());assertEquals(2,cart(c).path("items").get(0).path("quantity").asInt());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM customer_order WHERE customer_id=?",Integer.class,c.id()));assertEquals(2,place(c,body).path("items").get(0).path("quantity").asInt());
    }
    @Test void cancelledAndExpiryCompetitionReleasesExactlyOnceEvenWhenCustomerDisabled() throws Exception{
        var admin=admin();var f=fixture(admin,3);var c=customer();var body=ready(c,f,2);var order=place(c,body);due(order);
        assertEquals(List.of(200,200),race(()->cancel(c,order).getResponse().getStatus(),()->{orderService.expire(c.id(),order.path("id").asText());return 200;}));assertEquals(0,reserved(f));assertEquals("EXPIRED",place(c,body).path("status").asText());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM order_stock_event WHERE order_id=? AND action='RELEASE'",Integer.class,order.path("id").asText()));
        var next=place(c,ready(c,f,1));due(next);jdbc.update("UPDATE customer SET enabled=FALSE WHERE id=?",c.id());expiry.run();expiry.run();assertEquals(0,reserved(f));assertEquals("EXPIRED",jdbc.queryForObject("SELECT status FROM customer_order WHERE id=?",String.class,next.path("id").asText()));
    }
    @Test void cancelChecksPendingVersionAndConcurrentCancellationIsIdempotent() throws Exception{
        var admin=admin();var f=fixture(admin,3);var c=customer();var order=place(c,ready(c,f,2));String path="/customer/orders/"+order.path("id").asText()+"/cancel";
        assertEquals(409,call(c,"POST",path,Map.of("expectedVersion",2)).getResponse().getStatus());assertEquals(2,reserved(f));assertEquals(List.of(200,200),race(()->cancel(c,order).getResponse().getStatus(),()->cancel(c,order).getResponse().getStatus()));assertEquals(0,reserved(f));
    }
    @Test void releaseLedgerFailurePreservesPendingOrderAndReservedStock() throws Exception{
        var admin=admin();var f=fixture(admin,3);var c=customer();var order=place(c,ready(c,f,2));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic release ledger failure")).when(orderMapper).event(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq("RELEASE"),org.mockito.ArgumentMatchers.anyInt());
        assertEquals(500,cancel(c,order).getResponse().getStatus());org.mockito.Mockito.reset(orderMapper);
        assertEquals(2,reserved(f));assertEquals(order,data(call(c,"GET","/customer/orders/"+order.path("id").asText(),null)));assertEquals(200,cancel(c,order).getResponse().getStatus());assertEquals(0,reserved(f));
    }
    @Test void cartVersionsAboveIntegerCacheAreComparedByValue() throws Exception{
        var admin=admin();var f=fixture(admin,3);var c=customer();var body=ready(c,f,1);
        jdbc.update("UPDATE customer_cart SET version=256 WHERE customer_id=?",c.id());body.put("cartVersion",256);
        assertEquals("PENDING_PAYMENT",place(c,body).path("status").asText());assertEquals(257,cart(c).path("version").asInt());assertEquals(1,reserved(f));
    }
    @Test void newMigrationPreservesEarlierChecksumsAndStockSemantics(){
        assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE AND version IN ('1','2','3','4','5','6')",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sku_inventory WHERE reserved<0 OR reserved>quantity",Integer.class));
    }
    private List<Integer> race(Callable<Integer> a,Callable<Integer> b) throws Exception{var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var one=pool.submit(()->{start.await();return a.call();});var two=pool.submit(()->{start.await();return b.call();});start.countDown();return List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
}
