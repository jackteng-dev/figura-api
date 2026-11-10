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
import dev.jackteng.figura.service.InventoryService;
import dev.jackteng.figura.dto.InventoryPayloads;
import dev.jackteng.figura.pojo.CustomUserDetails;
import java.sql.SQLException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc(print=MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named="SHOP_MYSQL_INTEGRATION",matches="true")
class InventoryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired InventoryService inventory;
    @org.springframework.boot.test.mock.mockito.SpyBean dev.jackteng.figura.mapper.InventoryMapper inventoryMapper;
    @Autowired StringRedisTemplate redis;
    private final Set<String> products=ConcurrentHashMap.newKeySet(), sessions=ConcurrentHashMap.newKeySet();
    record Client(String csrf,List<Cookie> cookies,int id) {}
    record Fixture(String productId,String skuId) {String path(){return "/admin/products/"+productId+"/skus/"+skuId+"/inventory";}}
    private JsonNode data(MvcResult r) throws Exception{return json.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");}
    private Cookie cookie(MvcResult r,String name){var h=r.getResponse().getHeaders("Set-Cookie").stream().filter(v->v.startsWith(name+"=")).findFirst().orElseThrow();return new Cookie(name,h.split(";",2)[0].substring(name.length()+1));}
    private MvcResult call(Client c,String method,String path,Object body) throws Exception{
        var b=request(org.springframework.http.HttpMethod.valueOf(method),"/figura/api"+path).contextPath("/figura").servletPath("/api"+path.split("\\?",2)[0]);
        if(c!=null){b.cookie(c.cookies().toArray(Cookie[]::new));if(!method.equals("GET"))b.header("X-XSRF-TOKEN",c.csrf());}
        if(body!=null)b.contentType("application/json").content(json.writeValueAsBytes(body));return mvc.perform(b).andReturn();
    }
    private Client guest() throws Exception{var r=call(null,"GET","/auth/csrf",null);return new Client(data(r).path("token").asText(),List.of(cookie(r,"XSRF-TOKEN")),0);}
    private Client login(boolean admin) throws Exception{
        var c=guest();String device=UUID.randomUUID().toString();var r=mvc.perform(post("/figura/api/admin/login").contextPath("/figura").servletPath("/api/admin/login").cookie(c.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN",c.csrf()).param("username",admin?"shop_admin":"shop_staff").param("password",System.getenv(admin?"DEMO_ADMIN_PASSWORD":"DEMO_STAFF_PASSWORD")).param("deviceId",device)).andReturn();assertEquals(200,r.getResponse().getStatus());int id=data(r).path("id").asInt();sessions.add("shop:session:"+id+":"+device);return new Client(c.csrf(),List.of(c.cookies().get(0),cookie(r,"accessToken"),cookie(r,"refreshToken")),id);
    }
    private Fixture create(Client c) throws Exception{
        var r=call(c,"POST","/admin/products",Map.of("title","Inventory test "+UUID.randomUUID(),"brand","ELPSY","category","公仔","description","測試資料","expectedVersion",0,"skus",List.of(Map.of("code","INV-"+UUID.randomUUID(),"label","標準款","priceTwd",1280,"enabled",true))));assertEquals(201,r.getResponse().getStatus());var p=data(r);String id=p.path("id").asText();products.add(id);return new Fixture(id,p.path("skus").get(0).path("id").asText());
    }
    private Map<String,Object> input(int version,int delta,String reason){return new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",version,"delta",delta,"reason",reason,"note","整合測試入庫／盤點"));}
    private MvcResult adjust(Client c,Fixture f,Map<String,Object> body) throws Exception{return call(c,"POST",f.path()+"/adjustments",body);}
    private JsonNode balance(Client c,Fixture f) throws Exception{var r=call(c,"GET",f.path(),null);assertEquals(200,r.getResponse().getStatus());return data(r);}
    private JsonNode history(Client c,Fixture f) throws Exception{return data(call(c,"GET",f.path()+"/adjustments",null));}
    @AfterEach void cleanup(){
        // Audit rows are retained. Archive only owned synthetic products; disposable DB teardown removes fixtures.
        for(String id:products)jdbc.update("UPDATE catalog_product SET status='ARCHIVED' WHERE id=?",id);
        if(!sessions.isEmpty())redis.delete(sessions);
    }
    @Test @EnabledIfEnvironmentVariable(named="SHOP_MIGRATION_FIXTURE_SKU",matches=".+")
    void v3ExistingSkuBackfilledWithoutInventingStockOrChangingIdentity() throws Exception{
        String sku=System.getenv("SHOP_MIGRATION_FIXTURE_SKU");var c=login(true);String product=jdbc.queryForObject("SELECT product_id FROM catalog_sku WHERE id=?",String.class,sku);
        var b=balance(c,new Fixture(product,sku));assertEquals(0,b.path("quantity").asInt());assertEquals(0,b.path("version").asInt());assertEquals(1280,jdbc.queryForObject("SELECT price_twd FROM catalog_sku WHERE id=?",Integer.class,sku));assertEquals("Migration preserved SKU",jdbc.queryForObject("SELECT title FROM catalog_product WHERE id=?",String.class,product));assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE AND version IN ('1','2','3','4')",Integer.class));
    }
    @Test void restockDamageAuditAndPublicAvailabilityDoNotLeakQuantity() throws Exception{
        var c=login(true);var f=create(c);assertEquals(0,balance(c,f).path("quantity").asInt());
        assertEquals(200,call(c,"PATCH","/admin/products/"+f.productId()+"/publication",Map.of("expectedVersion",0,"published",true)).getResponse().getStatus());
        assertFalse(data(call(null,"GET","/catalog/products/"+f.productId(),null)).path("skus").get(0).path("available").asBoolean());
        var body=input(0,5,"RESTOCK");var r=adjust(c,f,body);assertEquals(201,r.getResponse().getStatus());assertFalse(data(r).path("replayed").asBoolean());var a=data(r).path("adjustment");assertEquals(c.id(),a.path("actorId").asInt());assertEquals("shop_admin",a.path("actorUsername").asText());assertEquals(0,a.path("beforeQuantity").asInt());assertEquals(5,a.path("afterQuantity").asInt());assertEquals(1,a.path("afterVersion").asInt());
        var publicSku=data(call(null,"GET","/catalog/products/"+f.productId(),null)).path("skus").get(0);assertTrue(publicSku.path("available").asBoolean());assertFalse(publicSku.has("quantity"));assertFalse(publicSku.has("version"));
        assertEquals(201,adjust(c,f,input(1,-5,"DAMAGE")).getResponse().getStatus());assertFalse(data(call(null,"GET","/catalog/products/"+f.productId(),null)).path("skus").get(0).path("available").asBoolean());assertEquals(2,history(c,f).path("total").asInt());assertEquals(1,jdbc.queryForObject("SELECT version FROM catalog_product WHERE id=?",Integer.class,f.productId()));
    }
    @Test void readAdjustAuditPermissionsCsrfAndUnknownActorAreEnforced() throws Exception{
        var admin=login(true);var staff=login(false);var f=create(staff);var body=input(0,2,"RESTOCK");
        assertEquals(200,call(staff,"GET",f.path(),null).getResponse().getStatus());assertEquals(200,call(staff,"GET",f.path()+"/adjustments",null).getResponse().getStatus());assertEquals(403,adjust(staff,f,body).getResponse().getStatus());assertEquals(401,call(null,"GET",f.path(),null).getResponse().getStatus());
        assertEquals(403,adjust(new Client("wrong",admin.cookies(),admin.id()),f,body).getResponse().getStatus());
        for(String field:List.of("actorId","actorUsername","quantity","afterQuantity")){var bad=new HashMap<>(body);bad.put(field,"spoofed");assertEquals(400,adjust(admin,f,bad).getResponse().getStatus());}
        jdbc.update("DELETE FROM role_permission WHERE role_id=1 AND permission_id=22");try{assertEquals(403,adjust(admin,f,body).getResponse().getStatus());assertEquals(200,call(admin,"GET",f.path(),null).getResponse().getStatus());}finally{jdbc.update("INSERT INTO role_permission VALUES(1,22)");}
        jdbc.update("DELETE FROM role_permission WHERE role_id=2 AND permission_id=21");try{assertEquals(403,call(staff,"GET",f.path()+"/adjustments",null).getResponse().getStatus());assertEquals(200,call(staff,"GET",f.path(),null).getResponse().getStatus());}finally{jdbc.update("INSERT INTO role_permission VALUES(2,21)");}
        assertEquals(0,balance(admin,f).path("version").asInt());
    }
    @Test void invalidNumbersReasonsAndDatabaseBoundsAreRejected() throws Exception{
        var c=login(true);var f=create(c);
        for(Object delta:List.of(0,1.5,1000001)){var b=input(0,1,"RESTOCK");b.put("delta",delta);assertEquals(400,adjust(c,f,b).getResponse().getStatus());}
        assertEquals(400,adjust(c,f,input(0,-1,"RESTOCK")).getResponse().getStatus());assertEquals(400,adjust(c,f,input(0,1,"DAMAGE")).getResponse().getStatus());
        var bad=input(0,1,"RESTOCK");bad.put("note"," ");assertEquals(400,adjust(c,f,bad).getResponse().getStatus());bad=input(0,1,"RESTOCK");bad.put("expectedVersion",0.5);assertEquals(400,adjust(c,f,bad).getResponse().getStatus());
        var negative=assertThrows(DataAccessException.class,()->jdbc.update("UPDATE sku_inventory SET quantity=-1 WHERE sku_id=?",f.skuId()));assertEquals(3819,((SQLException)negative.getMostSpecificCause()).getErrorCode());
        var overflow=assertThrows(DataAccessException.class,()->jdbc.update("UPDATE sku_inventory SET quantity=1000001 WHERE sku_id=?",f.skuId()));assertEquals(3819,((SQLException)overflow.getMostSpecificCause()).getErrorCode());assertEquals(0,balance(c,f).path("quantity").asInt());
        assertEquals(201,adjust(c,f,input(0,1000000,"RESTOCK")).getResponse().getStatus());assertEquals(409,adjust(c,f,input(1,1,"RESTOCK")).getResponse().getStatus());assertEquals(201,adjust(c,f,input(1,-1000000,"CORRECTION")).getResponse().getStatus());assertEquals(409,adjust(c,f,input(2,-1,"DAMAGE")).getResponse().getStatus());assertEquals(2,history(c,f).path("total").asInt());
    }
    @Test void concurrentDifferentOperationsOnSameVersionHaveOneWinner() throws Exception{
        var c=login(true);var f=create(c);var one=input(0,3,"RESTOCK");var two=input(0,3,"RESTOCK");var statuses=race(()->adjust(c,f,one).getResponse().getStatus(),()->adjust(c,f,two).getResponse().getStatus());assertEquals(Set.of(201,409),new HashSet<>(statuses));assertEquals(3,balance(c,f).path("quantity").asInt());assertEquals(1,balance(c,f).path("version").asInt());assertEquals(1,history(c,f).path("total").asInt());
    }
    @Test void concurrentRetryIsAppliedOnceAndHistoricalReplayDoesNotRestoreOldQuantity() throws Exception{
        var c=login(true);var f=create(c);var body=input(0,5,"RESTOCK");var statuses=race(()->adjust(c,f,body).getResponse().getStatus(),()->adjust(c,f,body).getResponse().getStatus());assertEquals(Set.of(201,200),new HashSet<>(statuses));assertEquals(5,balance(c,f).path("quantity").asInt());assertEquals(1,history(c,f).path("total").asInt());
        assertEquals(201,adjust(c,f,input(1,-2,"DAMAGE")).getResponse().getStatus());var replay=adjust(c,f,body);assertEquals(200,replay.getResponse().getStatus());assertTrue(data(replay).path("replayed").asBoolean());assertEquals(5,data(replay).path("adjustment").path("afterQuantity").asInt());assertEquals(3,balance(c,f).path("quantity").asInt());
        var changed=new HashMap<>(body);changed.put("delta",6);assertEquals(409,adjust(c,f,changed).getResponse().getStatus());
    }
    @Test void globalRequestIdCollisionRollsBackOtherSkuAndCannotCrossActor() throws Exception{
        var c=login(true);var one=create(c);var two=create(c);var body=input(0,4,"RESTOCK");var statuses=race(()->adjust(c,one,body).getResponse().getStatus(),()->adjust(c,two,body).getResponse().getStatus());assertEquals(Set.of(201,409),new HashSet<>(statuses));assertEquals(4,balance(c,one).path("quantity").asInt()+balance(c,two).path("quantity").asInt());assertEquals(1,history(c,one).path("total").asInt()+history(c,two).path("total").asInt());
        var winner=balance(c,one).path("quantity").asInt()==4?one:two;var staff=login(false);jdbc.update("INSERT INTO role_permission VALUES(2,22)");try{assertEquals(409,adjust(staff,winner,body).getResponse().getStatus());}finally{jdbc.update("DELETE FROM role_permission WHERE role_id=2 AND permission_id=22");}
    }
    @Test void ledgerWriteFailureRollsBackBalanceAndValidRetrySucceeds() throws Exception{
        var c=login(true);var f=create(c);var body=input(0,7,"RESTOCK");
        // Invalid actor is now rejected before any stock write. Inject a ledger failure with a valid actor.
        var payload=json.convertValue(body,InventoryPayloads.Adjust.class);
        assertEquals(401,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->inventory.adjust(f.productId(),f.skuId(),payload,new CustomUserDetails("nonexistent-test-actor",Integer.MAX_VALUE,List.of()))).getStatusCode().value());
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic inventory ledger failure")).when(inventoryMapper).append(org.mockito.ArgumentMatchers.any());
        try{assertEquals(500,adjust(c,f,body).getResponse().getStatus());}finally{org.mockito.Mockito.reset(inventoryMapper);}
        assertEquals(0,balance(c,f).path("quantity").asInt());assertEquals(0,balance(c,f).path("version").asInt());assertEquals(0,history(c,f).path("total").asInt());
        assertEquals(201,adjust(c,f,body).getResponse().getStatus());assertEquals(7,balance(c,f).path("quantity").asInt());
    }
    @Test void skuOwnershipCatalogEditsAndNewSkuInitializationPreserveStock() throws Exception{
        var c=login(true);var f=create(c);var other=create(c);var wrong=new Fixture(other.productId(),f.skuId());assertEquals(404,call(c,"GET",wrong.path(),null).getResponse().getStatus());assertEquals(404,adjust(c,wrong,input(0,1,"RESTOCK")).getResponse().getStatus());assertEquals(201,adjust(c,f,input(0,4,"RESTOCK")).getResponse().getStatus());
        var p=data(call(c,"GET","/admin/products/"+f.productId(),null));var skus=new ArrayList<Map<String,Object>>(json.convertValue(p.path("skus"),List.class));var body=new HashMap<String,Object>(Map.of("title",p.path("title").asText(),"brand","ELPSY","category","公仔","description","編輯內容","expectedVersion",0,"skus",skus));skus.get(0).put("quantity",999);assertEquals(400,call(c,"PUT","/admin/products/"+f.productId(),body).getResponse().getStatus());skus.get(0).remove("quantity");skus.get(0).put("enabled",false);skus.add(new HashMap<>(Map.of("code","NEW-"+UUID.randomUUID(),"label","新增款","priceTwd",999,"enabled",true)));
        var r=call(c,"PUT","/admin/products/"+f.productId(),body);assertEquals(200,r.getResponse().getStatus());assertEquals(4,balance(c,f).path("quantity").asInt());assertEquals(1,balance(c,f).path("version").asInt());var fresh=data(r).path("skus");for(var s:fresh)if(!s.path("id").asText().equals(f.skuId()))assertEquals(0,balance(c,new Fixture(f.productId(),s.path("id").asText())).path("quantity").asInt());
    }
    @Test void archivedAuditIsReadableAndCannotBeEditedOrDeletedThroughApi() throws Exception{
        var c=login(true);var f=create(c);var body=input(0,3,"RESTOCK");assertEquals(201,adjust(c,f,body).getResponse().getStatus());assertEquals(200,call(c,"DELETE","/admin/products/"+f.productId()+"?expectedVersion=0",null).getResponse().getStatus());
        assertEquals(409,adjust(c,f,input(1,1,"RESTOCK")).getResponse().getStatus());assertEquals(200,adjust(c,f,body).getResponse().getStatus());assertEquals(3,balance(c,f).path("quantity").asInt());assertEquals(1,history(c,f).path("total").asInt());
        assertEquals(403,call(c,"PUT",f.path()+"/adjustments",body).getResponse().getStatus());assertEquals(403,call(c,"DELETE",f.path()+"/adjustments/"+body.get("requestId"),null).getResponse().getStatus());assertEquals(1,history(c,f).path("total").asInt());
    }
    @Test void auditPaginationIsBoundedAndPreservesBeforeAfterChain() throws Exception{
        var c=login(true);var f=create(c);for(int version=0;version<3;version++)assertEquals(201,adjust(c,f,input(version,1,"RESTOCK")).getResponse().getStatus());
        var first=data(call(c,"GET",f.path()+"/adjustments?page=1&pageSize=2",null));var second=data(call(c,"GET",f.path()+"/adjustments?page=2&pageSize=2",null));assertEquals(3,first.path("total").asInt());assertEquals(2,first.path("items").size());assertEquals(1,second.path("items").size());assertEquals(3,first.path("items").get(0).path("afterQuantity").asInt());assertEquals(0,second.path("items").get(0).path("beforeQuantity").asInt());assertEquals(400,call(c,"GET",f.path()+"/adjustments?pageSize=51",null).getResponse().getStatus());
    }
    private List<Integer> race(Callable<Integer> a,Callable<Integer> b) throws Exception{var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var one=pool.submit(()->{start.await();return a.call();});var two=pool.submit(()->{start.await();return b.call();});start.countDown();return List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
}
