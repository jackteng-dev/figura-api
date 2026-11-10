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
class ShoppingIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
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
        for(String id:customers)jdbc.update("DELETE FROM customer WHERE id=?",id);
        for(String id:products)jdbc.update("UPDATE catalog_product SET status='ARCHIVED' WHERE id=?",id);
        if(!sessions.isEmpty())redis.delete(sessions);
    }
    @Test void cartOwnerIsolationServerPricesAndNoStockReservation() throws Exception{
        var admin=admin();var f=fixture(admin,9);var a=customer();var b=customer();assertEquals(0,cart(a).path("version").asInt());
        var r=call(a,"PUT",f.cart(),quantity(0,2));assertEquals(200,r.getResponse().getStatus());var view=data(r);assertEquals(2560,view.path("subtotalTwd").asLong());assertTrue(view.path("ready").asBoolean());
        assertEquals(9,jdbc.queryForObject("SELECT quantity FROM sku_inventory WHERE sku_id=?",Integer.class,f.sku()));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM inventory_adjustment WHERE sku_id=?",Integer.class,f.sku()));
        assertEquals(0,data(call(b,"GET","/customer/cart?customerId="+a.id(),null)).path("items").size());assertEquals(404,call(b,"DELETE",f.cart()+"?expectedVersion=0",null).getResponse().getStatus());assertEquals(2,cart(a).path("items").get(0).path("quantity").asInt());
        for(String field:List.of("customerId","unitPriceTwd","subtotalTwd","stock")){var body=quantity(1,3);body.put(field,a.id());assertEquals(400,call(a,"PUT",f.cart(),body).getResponse().getStatus());}
        assertEquals(1,cart(a).path("version").asInt());
        var fields=json.convertValue(view.path("items").get(0),Map.class).keySet();assertFalse(fields.contains("stock"));assertFalse(fields.contains("customerId"));
    }
    @Test void unpublishedDisabledAndStockChangesAreRecheckedWithoutLeakingDraftEdits() throws Exception{
        var admin=admin();var f=fixture(admin,5);var c=customer();assertEquals(200,call(c,"PUT",f.cart(),quantity(0,4)).getResponse().getStatus());
        jdbc.update("UPDATE sku_inventory SET quantity=2 WHERE sku_id=?",f.sku());var v=cart(c);assertEquals("INSUFFICIENT_STOCK",v.path("items").get(0).path("issue").asText());assertEquals(0,v.path("subtotalTwd").asLong());assertFalse(v.path("ready").asBoolean());
        assertEquals(409,call(c,"PUT",f.cart(),quantity(1,4)).getResponse().getStatus());assertEquals(200,call(c,"PUT",f.cart(),quantity(1,2)).getResponse().getStatus());
        jdbc.update("UPDATE catalog_product SET status='DRAFT',title='Private draft title' WHERE id=?",f.product());jdbc.update("UPDATE catalog_sku SET price_twd=9900,label='Private draft label' WHERE id=?",f.sku());v=cart(c);var line=v.path("items").get(0);assertEquals("UNAVAILABLE",line.path("issue").asText());assertTrue(line.path("unitPriceTwd").isNull());assertFalse(v.toString().contains("Private draft"));assertEquals(409,call(c,"PUT",f.cart(),quantity(2,1)).getResponse().getStatus());
        jdbc.update("UPDATE catalog_product SET status='PUBLISHED' WHERE id=?",f.product());v=cart(c);assertEquals(19800,v.path("subtotalTwd").asLong());
        jdbc.update("UPDATE catalog_sku SET enabled=FALSE WHERE id=?",f.sku());assertEquals("UNAVAILABLE",cart(c).path("items").get(0).path("issue").asText());assertEquals(409,call(c,"PUT",f.cart(),quantity(2,1)).getResponse().getStatus());
        jdbc.update("UPDATE catalog_sku SET enabled=TRUE WHERE id=?",f.sku());jdbc.update("UPDATE sku_inventory SET quantity=0 WHERE sku_id=?",f.sku());assertEquals("SOLD_OUT",cart(c).path("items").get(0).path("issue").asText());assertEquals(200,call(c,"DELETE",f.cart()+"?expectedVersion=2",null).getResponse().getStatus());assertEquals(0,cart(c).path("items").size());
    }
    @Test void integerLimitsCsrfRealmsDisabledAccountsAndUnknownRoutes() throws Exception{
        var admin=admin();var f=fixture(admin,200);var c=customer();
        assertEquals(401,call(admin,"GET","/customer/cart",null).getResponse().getStatus());assertEquals(401,call(guest(),"GET","/customer/addresses",null).getResponse().getStatus());assertEquals(401,call(c,"GET","/auth/me",null).getResponse().getStatus());
        assertEquals(403,call(new Client("wrong",c.cookies(),c.id()),"PUT",f.cart(),quantity(0,1)).getResponse().getStatus());
        for(Object qty:List.of(0,-1,100,1.5,"bad")){var body=quantity(0,1);body.put("quantity",qty);assertEquals(400,call(c,"PUT",f.cart(),body).getResponse().getStatus());}
        assertEquals(400,call(c,"PUT","/customer/cart/items/bad",quantity(0,1)).getResponse().getStatus());assertEquals(404,call(c,"PUT","/customer/cart/items/"+UUID.randomUUID(),quantity(0,1)).getResponse().getStatus());assertEquals(403,call(c,"POST","/customer/cart",quantity(0,1)).getResponse().getStatus());
        assertEquals(200,call(c,"PUT",f.cart(),quantity(0,99)).getResponse().getStatus());jdbc.update("UPDATE customer SET enabled=FALSE WHERE id=?",c.id());assertEquals(401,call(c,"GET","/customer/cart",null).getResponse().getStatus());assertEquals(401,call(c,"GET","/customer/addresses",null).getResponse().getStatus());
    }
    @Test void cartConcurrentAbsoluteWritesStaleDeleteAndRetryCannotDoubleAdd() throws Exception{
        var admin=admin();var f=fixture(admin,100);var c=customer();var statuses=race(()->call(c,"PUT",f.cart(),quantity(0,2)).getResponse().getStatus(),()->call(c,"PUT",f.cart(),quantity(0,3)).getResponse().getStatus());assertEquals(Set.of(200,409),new HashSet<>(statuses));var v=cart(c);assertEquals(1,v.path("version").asInt());assertEquals(1,v.path("items").size());
        int qty=v.path("items").get(0).path("quantity").asInt();assertEquals(409,call(c,"PUT",f.cart(),quantity(0,qty)).getResponse().getStatus());assertEquals(qty,cart(c).path("items").get(0).path("quantity").asInt());
        assertEquals(409,call(c,"DELETE",f.cart()+"?expectedVersion=0",null).getResponse().getStatus());assertEquals(200,call(c,"DELETE",f.cart()+"?expectedVersion=1",null).getResponse().getStatus());assertEquals(404,call(c,"DELETE",f.cart()+"?expectedVersion=2",null).getResponse().getStatus());assertEquals(2,cart(c).path("version").asInt());
    }
    @Test void addressOwnerIsolationProjectionValidationAndPersistentCrud() throws Exception{
        var a=customer();var b=customer();String id=UUID.randomUUID().toString(),path="/customer/addresses/"+id;var r=call(a,"PUT",path,address(0));assertEquals(200,r.getResponse().getStatus());var v=data(r);assertEquals(1,v.path("version").asInt());assertFalse(v.has("customerId"));assertEquals(0,data(call(b,"GET","/customer/addresses?customerId="+a.id(),null)).size());
        assertEquals(404,call(b,"PUT",path,address(0)).getResponse().getStatus());assertEquals(404,call(b,"DELETE",path+"?expectedVersion=1",null).getResponse().getStatus());
        var body=address(1);body.put("recipientName","<img src=x onerror=alert(1)>");assertEquals(200,call(a,"PUT",path,body).getResponse().getStatus());assertEquals(body.get("recipientName"),data(call(a,"GET","/customer/addresses",null)).get(0).path("recipientName").asText());
        for(String field:List.of("customerId","id","country","isDefault")){var bad=address(2);bad.put(field,a.id());assertEquals(400,call(a,"PUT",path,bad).getResponse().getStatus());}
        for(String field:List.of("phone","postalCode","city","street")){var bad=address(2);bad.put(field," ");assertEquals(400,call(a,"PUT",path,bad).getResponse().getStatus());}
        assertEquals(403,call(new Client("wrong",a.cookies(),a.id()),"DELETE",path+"?expectedVersion=2",null).getResponse().getStatus());assertEquals(200,call(a,"DELETE",path+"?expectedVersion=2",null).getResponse().getStatus());assertEquals(0,data(call(a,"GET","/customer/addresses",null)).size());
        assertEquals(409,call(a,"PUT",path,address(0)).getResponse().getStatus());assertEquals("",jdbc.queryForObject("SELECT street FROM customer_address WHERE id=?",String.class,id));assertEquals(404,call(a,"DELETE",path+"?expectedVersion=3",null).getResponse().getStatus());
    }
    @Test void addressVersionRacesAndMaximumAreSerialized() throws Exception{
        var c=customer();String id=UUID.randomUUID().toString(),path="/customer/addresses/"+id;
        assertEquals(Set.of(200,409),new HashSet<>(race(()->call(c,"PUT",path,address(0)).getResponse().getStatus(),()->call(c,"PUT",path,address(0)).getResponse().getStatus())));
        assertEquals(Set.of(200,409),new HashSet<>(race(()->call(c,"PUT",path,address(1)).getResponse().getStatus(),()->call(c,"PUT",path,address(1)).getResponse().getStatus())));
        assertEquals(409,call(c,"DELETE",path+"?expectedVersion=1",null).getResponse().getStatus());assertEquals(2,data(call(c,"GET","/customer/addresses",null)).get(0).path("version").asInt());
        for(int i=0;i<8;i++)assertEquals(200,call(c,"PUT","/customer/addresses/"+UUID.randomUUID(),address(0)).getResponse().getStatus());
        assertEquals(Set.of(200,409),new HashSet<>(race(()->call(c,"PUT","/customer/addresses/"+UUID.randomUUID(),address(0)).getResponse().getStatus(),()->call(c,"PUT","/customer/addresses/"+UUID.randomUUID(),address(0)).getResponse().getStatus())));assertEquals(10,data(call(c,"GET","/customer/addresses",null)).size());
    }
    @Test void databaseQuantityCheckAndCustomerCascadeOnlyRemoveOwnedData() throws Exception{
        var admin=admin();var f=fixture(admin,3);var a=customer();var b=customer();for(var c:List.of(a,b)){assertEquals(200,call(c,"PUT",f.cart(),quantity(0,1)).getResponse().getStatus());assertEquals(200,call(c,"PUT","/customer/addresses/"+UUID.randomUUID(),address(0)).getResponse().getStatus());}
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE customer_cart_item SET quantity=0 WHERE customer_id=?",a.id()));assertEquals(1,cart(a).path("items").get(0).path("quantity").asInt());
        jdbc.update("DELETE FROM customer WHERE id=?",a.id());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM customer_cart WHERE customer_id=?",Integer.class,a.id()));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM customer_address WHERE customer_id=?",Integer.class,a.id()));assertEquals(1,cart(b).path("items").size());assertEquals(1,data(call(b,"GET","/customer/addresses",null)).size());assertEquals(3,jdbc.queryForObject("SELECT quantity FROM sku_inventory WHERE sku_id=?",Integer.class,f.sku()));
    }
    @Test void cartCapacityRaceCannotCreateFiftyFirstLine() throws Exception{
        var admin=admin();var f=fixture(admin,10);var c=customer();var ids=new ArrayList<String>();ids.add(f.sku());
        // Additional owned synthetic SKUs use real SQL to avoid 50 unrelated inventory audit operations.
        for(int i=0;i<49;i++){String sku=UUID.randomUUID().toString();ids.add(sku);jdbc.update("INSERT INTO catalog_sku(id,product_id,code,label,price_twd,enabled) VALUES(?,?,?,'容量測試',1280,TRUE)",sku,f.product(),"CAP-"+sku);jdbc.update("INSERT INTO sku_inventory(sku_id,quantity) VALUES(?,10)",sku);}
        for(int i=0;i<49;i++)assertEquals(200,call(c,"PUT","/customer/cart/items/"+ids.get(i),quantity(i,1)).getResponse().getStatus());
        var other=fixture(admin,10);assertEquals(Set.of(200,409),new HashSet<>(race(()->call(c,"PUT","/customer/cart/items/"+ids.get(49),quantity(49,1)).getResponse().getStatus(),()->call(c,"PUT",other.cart(),quantity(49,1)).getResponse().getStatus())));
        var v=cart(c);assertEquals(50,v.path("items").size());String missing=v.path("items").toString().contains(other.sku())?ids.get(49):other.sku();assertEquals(409,call(c,"PUT","/customer/cart/items/"+missing,quantity(50,1)).getResponse().getStatus());assertEquals(50,cart(c).path("version").asInt());assertEquals(200,call(c,"PUT","/customer/cart/items/"+ids.get(0),quantity(50,2)).getResponse().getStatus());
    }
    @Test @EnabledIfEnvironmentVariable(named="SHOP_V5_FIXTURE_CUSTOMER",matches=".+")
    void v4ToV5PreservesCustomerAndSkuAndStartsWithoutFakeShoppingData(){
        String id=System.getenv("SHOP_V5_FIXTURE_CUSTOMER"),sku=System.getenv("SHOP_MIGRATION_FIXTURE_SKU");assertEquals("Migration customer",jdbc.queryForObject("SELECT display_name FROM customer WHERE id=?",String.class,id));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM customer_cart_item WHERE customer_id=?",Integer.class,id));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM customer_address WHERE customer_id=?",Integer.class,id));assertEquals(1280,jdbc.queryForObject("SELECT price_twd FROM catalog_sku WHERE id=?",Integer.class,sku));assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE AND version IN ('1','2','3','4','5')",Integer.class));
    }
    private List<Integer> race(Callable<Integer> a,Callable<Integer> b) throws Exception{var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var one=pool.submit(()->{start.await();return a.call();});var two=pool.submit(()->{start.await();return b.call();});start.countDown();return List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
}
