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

/** 綠界測試付款：真實隔離 MySQL／Redis 與完整安全鏈；回呼以測試金鑰簽章模擬綠界伺服器。 */
@SpringBootTest(properties={"ECPAY_ENABLED=true","ECPAY_MERCHANT_ID=3002607","ECPAY_HASH_KEY=test-only-hash-key","ECPAY_HASH_IV=test-only-hash-iv","ECPAY_RETURN_URL=https://shop.example.test/figura/api/payments/ecpay/callback","ECPAY_CLIENT_BACK_URL=https://shop.example.test/shop/orders"}) @AutoConfigureMockMvc(print=MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named="SHOP_MYSQL_INTEGRATION",matches="true")
class EcpayIntegrationTest {
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
    private static String tradeNo(){return Long.toString(1_000_000_000_000_000L+(long)(Math.random()*8_000_000_000_000_000L));}
    private int physical(Fixture f){return jdbc.queryForObject("SELECT quantity FROM sku_inventory WHERE sku_id=?",Integer.class,f.sku());}
    private String body(MvcResult r) throws Exception{return r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);}
    private Map<String,Object> start(JsonNode order){return new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"expectedOrderVersion",order.path("version").asInt()));}
    private String startPath(JsonNode order){return "/customer/orders/"+order.path("id").asText()+"/ecpay-payments";}
    private JsonNode ecpayStart(Client c,JsonNode order,Map<String,Object> input) throws Exception{var r=call(c,"POST",startPath(order),input);assertEquals(200,r.getResponse().getStatus(),body(r));return data(r);}
    /** 綠界 ReturnURL 會帶的欄位（信用卡成功）。 */
    private Map<String,String> paid(JsonNode started,String gatewayNo){
        var f=started.path("checkout").path("fields");var m=new TreeMap<String,String>();
        m.put("MerchantID","3002607");m.put("MerchantTradeNo",f.path("MerchantTradeNo").asText());m.put("StoreID","");m.put("RtnCode","1");m.put("RtnMsg","交易成功");m.put("TradeNo",gatewayNo);
        m.put("TradeAmt",f.path("TotalAmount").asText());m.put("PaymentDate","2026/11/17 10:00:00");m.put("PaymentType","Credit_CreditCard");m.put("PaymentTypeChargeFee","0");m.put("TradeDate","2026/11/17 09:59:00");m.put("SimulatePaid","0");
        m.put("CustomField1","");m.put("CustomField2","");m.put("CustomField3","");m.put("CustomField4","");return m;
    }
    private Map<String,String> sign(Map<String,String> m){var s=new TreeMap<>(m);s.remove("CheckMacValue");s.put("CheckMacValue",ecpay.checkMac(s));return s;}
    private MvcResult callback(Map<String,String> params,String query) throws Exception{
        var b=post("/figura/api/payments/ecpay/callback"+(query==null?"":"?"+query)).contextPath("/figura").servletPath("/api/payments/ecpay/callback").contentType("application/x-www-form-urlencoded");
        params.forEach((k,v)->b.param(k,v));return mvc.perform(b).andReturn();
    }
    private String status(JsonNode order){return jdbc.queryForObject("SELECT status FROM customer_order WHERE id=?",String.class,order.path("id").asText());}
    private String paymentStatus(JsonNode started){return jdbc.queryForObject("SELECT status FROM mock_payment WHERE id=?",String.class,started.path("payment").path("id").asText());}
    private int consumes(JsonNode order){return jdbc.queryForObject("SELECT COUNT(*) FROM order_stock_event WHERE order_id=? AND action='CONSUME'",Integer.class,order.path("id").asText());}
    private int events(JsonNode started){return jdbc.queryForObject("SELECT COUNT(*) FROM mock_payment_event WHERE payment_id=?",Integer.class,started.path("payment").path("id").asText());}

    @Test void startReturnsSignedStageFormFromServerSnapshotAndReplaysSameRequest() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=place(c,ready(c,f,2));var input=start(o);
        var s=ecpayStart(c,o,input);var fields=s.path("checkout").path("fields");
        assertEquals("https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5",s.path("checkout").path("action").asText());
        assertEquals("ECPAY",s.path("payment").path("provider").asText());assertEquals("INITIATED",s.path("payment").path("status").asText());assertFalse(s.path("replayed").asBoolean());
        assertEquals(Long.toString(o.path("totalTwd").asLong()),fields.path("TotalAmount").asText());assertEquals("2560",fields.path("TotalAmount").asText());
        assertTrue(fields.path("MerchantTradeNo").asText().matches("FG[0-9A-Z]{18}"));assertEquals("Credit",fields.path("ChoosePayment").asText());
        assertEquals("https://shop.example.test/shop/orders?ecpay="+o.path("id").asText(),fields.path("ClientBackURL").asText());
        assertFalse(body(call(c,"POST",startPath(o),input)).contains("test-only-hash"));
        var copy=new HashMap<String,String>();fields.fields().forEachRemaining(e->copy.put(e.getKey(),e.getValue().asText()));assertTrue(ecpay.verify(copy));
        var replay=ecpayStart(c,o,input);assertTrue(replay.path("replayed").asBoolean());assertEquals(s.path("payment").path("id").asText(),replay.path("payment").path("id").asText());assertEquals(fields.path("MerchantTradeNo").asText(),replay.path("checkout").path("fields").path("MerchantTradeNo").asText());
        input.put("expectedOrderVersion",o.path("version").asInt()+1);assertEquals(409,call(c,"POST",startPath(o),input).getResponse().getStatus());
        var other=customer();assertEquals(404,call(other,"POST",startPath(o),start(o)).getResponse().getStatus());
        assertEquals(403,call(new Client("wrong",c.cookies(),c.id()),"POST",startPath(o),start(o)).getResponse().getStatus());
        var opts=data(call(c,"GET","/customer/orders/"+o.path("id").asText()+"/mock-payments",null));assertEquals(1,opts.path("items").size());assertTrue(opts.path("ecpayCanStart").asBoolean());
        assertEquals(5,physical(f));assertEquals(2,reserved(f));
    }
    @Test void signedReturnUrlSettlesExactlyOnceWithoutCookieOrCsrf() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=place(c,ready(c,f,2));var s=ecpayStart(c,o,start(o));
        String no=tradeNo();var notice=sign(paid(s,no));var r=callback(notice,null);assertEquals(200,r.getResponse().getStatus());assertEquals("1|OK",body(r));
        assertEquals("PAID",status(o));assertEquals("SUCCEEDED",paymentStatus(s));assertEquals(3,physical(f));assertEquals(0,reserved(f));
        assertEquals(no,jdbc.queryForObject("SELECT gateway_trade_no FROM mock_payment WHERE id=?",String.class,s.path("payment").path("id").asText()));
        var lower=paid(s,no.substring(0,16)+"ab");lower.put("MerchantTradeNo",lower.get("MerchantTradeNo").toLowerCase());assertEquals("1|OK",body(callback(sign(lower),null)));
        var upper=paid(s,no.substring(0,16)+"AB");assertEquals("1|OK",body(callback(sign(upper),null)));assertEquals(2,events(s));
        assertEquals("1|OK",body(callback(notice,null)));assertEquals(1,consumes(o));assertEquals(3,physical(f));assertEquals(2,events(s));
        var current=data(call(c,"GET","/customer/orders/"+o.path("id").asText(),null));assertEquals(409,call(c,"POST",startPath(o),start(current)).getResponse().getStatus());
    }
    @Test void forgedTamperedOrMismatchedNoticesChangeNothing() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=place(c,ready(c,f,1));var s=ecpayStart(c,o,start(o));
        String no=tradeNo();var good=sign(paid(s,no));
        var forged=new TreeMap<>(good);forged.put("CheckMacValue","0".repeat(64));var r=callback(forged,null);assertEquals(401,r.getResponse().getStatus());assertEquals("0|CheckMacValue Error",body(r));
        var missing=new TreeMap<>(good);missing.remove("CheckMacValue");assertEquals(401,callback(missing,null).getResponse().getStatus());
        var tampered=new TreeMap<>(good);tampered.put("TradeAmt","1");assertEquals(401,callback(tampered,null).getResponse().getStatus());
        var cheaper=paid(s,no);cheaper.put("TradeAmt","1");assertEquals(409,callback(sign(cheaper),null).getResponse().getStatus());
        var merchant=paid(s,no);merchant.put("MerchantID","2000132");assertEquals(401,callback(sign(merchant),null).getResponse().getStatus());
        var unknown=paid(s,no);unknown.put("MerchantTradeNo","FGUNKNOWN0000000000A");assertEquals(404,callback(sign(unknown),null).getResponse().getStatus());
        assertEquals(400,callback(sign(paid(s,"")),null).getResponse().getStatus());
        assertEquals(400,callback(good,"RtnCode=1").getResponse().getStatus());
        var simulated=paid(s,no);simulated.put("SimulatePaid","1");assertEquals("1|OK",body(callback(sign(simulated),null)));
        var renamed=new TreeMap<>(sign(simulated));renamed.put("simulatepaid",renamed.remove("SimulatePaid"));assertEquals("1|OK",body(callback(renamed,null)));
        var doubled=new TreeMap<>(sign(paid(s,no)));doubled.put("simulatepaid","1");assertEquals(400,callback(doubled,null).getResponse().getStatus());
        var noFlag=paid(s,no);noFlag.remove("SimulatePaid");assertEquals(400,callback(sign(noFlag),null).getResponse().getStatus());
        assertEquals(401,call(guest(),"GET","/payments/ecpay/callback",null).getResponse().getStatus());
        assertEquals("PENDING_PAYMENT",status(o));assertEquals("INITIATED",paymentStatus(s));assertEquals(5,physical(f));assertEquals(1,reserved(f));assertEquals(0,events(s));
        if(gateway.enabled()){String pid=s.path("payment").path("id").asText();var mock=new HashMap<String,Object>(Map.of("eventId",UUID.randomUUID().toString(),"paymentId",pid,"transactionId","mock_"+pid,"amountTwd",1280,"currency","TWD","outcome","SUCCESS"));String raw=json.writeValueAsString(mock),ts=Long.toString(java.time.Instant.now().getEpochSecond());
            assertEquals(409,mvc.perform(post("/figura/api/payments/mock/callback").contextPath("/figura").servletPath("/api/payments/mock/callback").header("X-Mock-Timestamp",ts).header("X-Mock-Signature",gateway.sign(ts,raw)).contentType("application/json").content(raw)).andReturn().getResponse().getStatus());
            assertEquals(0,events(s));}
        var lowered=paid(s,no);lowered.put("MerchantTradeNo",lowered.get("MerchantTradeNo").toLowerCase());assertEquals("1|OK",body(callback(sign(lowered),null)));assertEquals("PAID",status(o));assertEquals(4,physical(f));
        assertEquals("1|OK",body(callback(good,null)));assertEquals(1,consumes(o));
    }
    @Test void restartSupersedesUnfinishedAttemptAndLateSuccessNeedsReview() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=place(c,ready(c,f,1));var first=ecpayStart(c,o,start(o));var second=ecpayStart(c,o,start(o));
        assertEquals("FAILED",paymentStatus(first));assertEquals("INITIATED",paymentStatus(second));assertEquals(1,events(first));
        assertNotEquals(first.path("checkout").path("fields").path("MerchantTradeNo").asText(),second.path("checkout").path("fields").path("MerchantTradeNo").asText());
        assertEquals("1|OK",body(callback(sign(paid(first,tradeNo())),null)));
        assertEquals("REVIEW_REQUIRED",paymentStatus(first));assertEquals("PENDING_PAYMENT",status(o));assertEquals(5,physical(f));assertEquals(0,consumes(o));
        assertFalse(data(call(c,"GET","/customer/orders/"+o.path("id").asText()+"/mock-payments",null)).path("ecpayCanStart").asBoolean());
        assertEquals("1|OK",body(callback(sign(paid(second,tradeNo())),null)));assertEquals("REVIEW_REQUIRED",paymentStatus(second));assertEquals("PENDING_PAYMENT",status(o));assertEquals(0,consumes(o));
    }
    @Test void replayedStartOfClosedAttemptHasNoCheckoutForm() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();var o=place(c,ready(c,f,1));var input=start(o);var first=ecpayStart(c,o,input);ecpayStart(c,o,start(o));
        var replay=ecpayStart(c,o,input);assertTrue(replay.path("replayed").asBoolean());assertEquals("FAILED",replay.path("payment").path("status").asText());assertTrue(replay.path("checkout").isNull());
        assertEquals(first.path("payment").path("id").asText(),replay.path("payment").path("id").asText());
    }
}
