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

/** 直接購買：真實隔離 MySQL／Redis 與完整安全鏈；只下單一款式，不動購物車。 */
@SpringBootTest @AutoConfigureMockMvc(print=MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named="SHOP_MYSQL_INTEGRATION",matches="true")
class DirectOrderIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired dev.jackteng.figura.service.OrderService orderService;
    @Autowired dev.jackteng.figura.service.OrderExpiryJob expiry;
    @Autowired dev.jackteng.figura.service.MockPaymentGateway gateway;
    @Autowired dev.jackteng.figura.service.EcpayGateway ecpay;
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
        var r=call(c,"POST","/customer/auth/register",Map.of("email",name+"@example.test","phone",TestPhones.next(),"password",password));assertEquals(201,r.getResponse().getStatus());String id=data(r).path("id").asText();customers.add(id);
        r=call(c,"POST","/customer/auth/login",Map.of("username",name+"@example.test","password",password,"deviceId",device,"rememberMe",false));assertEquals(200,r.getResponse().getStatus());sessions.add("shop:customer:session:"+id+":"+device);
        return new Client(c.csrf(),List.of(c.cookies().get(0),cookie(r,"customerAccessToken"),cookie(r,"customerRefreshToken")),id);
    }
    private Client admin() throws Exception{
        var c=guest();String device=UUID.randomUUID().toString();var r=StaffLogin.complete(mvc, () -> mvc.perform(post("/figura/api/admin/login").contextPath("/figura").servletPath("/api/admin/login").cookie(c.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN",c.csrf()).param("username","shop_admin").param("password",System.getenv("DEMO_ADMIN_PASSWORD")).param("deviceId",device)).andReturn());assertEquals(200,r.getResponse().getStatus());String id=data(r).path("id").asText();sessions.add("shop:session:"+id+":"+device);
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
    private String addressOf(Client c) throws Exception{String id=UUID.randomUUID().toString();assertEquals(200,call(c,"PUT","/customer/addresses/"+id,address(0)).getResponse().getStatus());return id;}
    private Map<String,Object> direct(Fixture f,int qty,String address,long subtotal){return new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"productId",f.product(),"skuId",f.sku(),"quantity",qty,"addressId",address,"addressVersion",1,"expectedSubtotalTwd",subtotal));}
    private MvcResult buy(Client c,Map<String,Object> body) throws Exception{return call(c,"POST","/customer/orders/direct",body);}

    @Test void directOrderReservesOnlyThatItemAndLeavesCartUntouched() throws Exception{
        var admin=admin();var f=fixture(admin,5);var other=fixture(admin,5);var c=customer();
        assertEquals(200,call(c,"PUT",other.cart(),quantity(cart(c).path("version").asInt(),2)).getResponse().getStatus());var before=cart(c);
        String address=addressOf(c);var body=direct(f,2,address,2560);var r=buy(c,body);assertEquals(200,r.getResponse().getStatus(),r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        var order=data(r).path("order");assertEquals("PENDING_PAYMENT",order.path("status").asText());assertEquals(2560,order.path("totalTwd").asLong());assertEquals(1,order.path("items").size());assertEquals(f.sku(),order.path("items").get(0).path("skuId").asText());
        assertEquals(2,reserved(f));assertEquals(0,reserved(other));assertEquals(5,physical(f));
        var after=cart(c);assertEquals(before.path("version").asInt(),after.path("version").asInt());assertEquals(1,after.path("items").size());assertEquals(other.sku(),after.path("items").get(0).path("skuId").asText());
        var replay=buy(c,body);assertEquals(200,replay.getResponse().getStatus());assertTrue(data(replay).path("replayed").asBoolean());assertEquals(order.path("id").asText(),data(replay).path("order").path("id").asText());assertEquals(2,reserved(f));
        var changed=new HashMap<>(body);changed.put("quantity",1);changed.put("expectedSubtotalTwd",1280);assertEquals(409,buy(c,changed).getResponse().getStatus());
        var cartPlace=new HashMap<String,Object>(Map.of("requestId",body.get("requestId"),"cartVersion",after.path("version").asInt(),"addressId",address,"addressVersion",1,"expectedSubtotalTwd",after.path("subtotalTwd").asLong()));
        assertEquals(409,call(c,"POST","/customer/orders",cartPlace).getResponse().getStatus());
        var zero=new HashMap<String,Object>(Map.of("requestId",body.get("requestId"),"cartVersion",0,"addressId",address,"addressVersion",1,"expectedSubtotalTwd",2560L));assertEquals(409,call(c,"POST","/customer/orders",zero).getResponse().getStatus());assertEquals(2,reserved(f));assertEquals(0,reserved(other));
        // 已有的購物車訂單操作編號也不能拿來直接購買
        var placed=place(c,new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"cartVersion",after.path("version").asInt(),"addressId",address,"addressVersion",1,"expectedSubtotalTwd",after.path("subtotalTwd").asLong())));
        var reuse=direct(f,1,address,1280);reuse.put("requestId",jdbc.queryForObject("SELECT request_id FROM customer_order WHERE id=?",String.class,placed.path("id").asText()));assertEquals(409,buy(c,reuse).getResponse().getStatus());
    }
    @Test void directOrderRechecksProductStockPriceAndOwnership() throws Exception{
        var admin=admin();var f=fixture(admin,3);var g=fixture(admin,3);var c=customer();var d=customer();String address=addressOf(c);
        assertEquals(409,buy(c,direct(f,4,address,5120)).getResponse().getStatus());
        assertEquals(409,buy(c,direct(f,1,address,1)).getResponse().getStatus());
        var mixed=direct(f,1,address,1280);mixed.put("skuId",g.sku());assertEquals(409,buy(c,mixed).getResponse().getStatus());
        assertEquals(404,buy(d,direct(f,1,address,1280)).getResponse().getStatus());
        assertEquals(400,buy(c,direct(f,0,address,0)).getResponse().getStatus());assertEquals(400,buy(c,direct(f,100,address,128000)).getResponse().getStatus());
        var extra=direct(f,1,address,1280);extra.put("unitPriceTwd",1);assertEquals(400,buy(c,extra).getResponse().getStatus());
        jdbc.update("UPDATE catalog_product SET status='ARCHIVED' WHERE id=?",g.product());assertEquals(409,buy(c,direct(g,1,address,1280)).getResponse().getStatus());
        assertEquals(403,call(new Client("wrong",c.cookies(),c.id()),"POST","/customer/orders/direct",direct(f,1,address,1280)).getResponse().getStatus());
        assertEquals(401,call(guest(),"POST","/customer/orders/direct",direct(f,1,address,1280)).getResponse().getStatus());
        assertEquals(0,reserved(f));assertEquals(0,reserved(g));
        var ok=buy(c,direct(f,3,address,3840));assertEquals(200,ok.getResponse().getStatus());assertEquals(3,reserved(f));assertEquals(409,buy(c,direct(f,1,address,1280)).getResponse().getStatus());
        var o=data(ok).path("order");assertEquals(200,cancel(c,o).getResponse().getStatus());assertEquals(0,reserved(f));
    }
}
