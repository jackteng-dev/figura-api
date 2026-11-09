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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Complete security chain + real isolated MySQL/Redis, never a production database. */
@SpringBootTest @AutoConfigureMockMvc(print=MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named="SHOP_MYSQL_INTEGRATION",matches="true")
class CatalogIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    private final Set<String> ids=ConcurrentHashMap.newKeySet();
    private final Set<String> sessionKeys=ConcurrentHashMap.newKeySet();
    record Client(String csrf,List<Cookie> cookies) {}
    private Cookie cookie(MvcResult r,String name){var header=r.getResponse().getHeaders("Set-Cookie").stream().filter(h->h.startsWith(name+"=")).findFirst().orElseThrow();return new Cookie(name,header.split(";",2)[0].substring(name.length()+1));}
    private JsonNode data(MvcResult r) throws Exception{return json.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");}
    private MvcResult call(Client c,String method,String path,Object body) throws Exception{
        var b=request(org.springframework.http.HttpMethod.valueOf(method),"/ELPSY/api"+path).contextPath("/ELPSY").servletPath("/api"+path.split("\\?",2)[0]);
        if(c!=null){b.cookie(c.cookies().toArray(Cookie[]::new));if(!method.equals("GET"))b.header("X-XSRF-TOKEN",c.csrf());}
        if(body!=null)b.contentType("application/json").content(json.writeValueAsBytes(body));
        return mvc.perform(b).andReturn();
    }
    private Client guest() throws Exception{var r=call(null,"GET","/auth/csrf",null);return new Client(data(r).path("token").asText(),List.of(cookie(r,"XSRF-TOKEN")));}
    private Client staff(boolean admin) throws Exception{
        var c=guest();String device=UUID.randomUUID().toString(),name=admin?"shop_admin":"shop_staff";
        var r=mvc.perform(post("/ELPSY/api/admin/login").contextPath("/ELPSY").servletPath("/api/admin/login").cookie(c.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN",c.csrf()).param("username",name).param("password",System.getenv(admin?"DEMO_ADMIN_PASSWORD":"DEMO_STAFF_PASSWORD")).param("deviceId",device)).andReturn();assertEquals(200,r.getResponse().getStatus());
        sessionKeys.add("shop:session:"+data(r).path("id").asInt()+":"+device);
        return new Client(c.csrf(),List.of(c.cookies().get(0),cookie(r,"accessToken"),cookie(r,"refreshToken")));
    }
    private Map<String,Object> sku(String code,boolean enabled){return new HashMap<>(Map.of("code",code,"label","初回限定款","priceTwd",1280,"enabled",enabled));}
    private Map<String,Object> product(List<Map<String,Object>> skus){return new HashMap<>(Map.of("title","integration collection "+UUID.randomUUID(),"brand","ELPSY","category","公仔","description","<script>資料是文字</script>","expectedVersion",0,"skus",skus));}
    private String code(){return "TEST-"+UUID.randomUUID();}
    private JsonNode create(Client c,Map<String,Object> input) throws Exception{var r=call(c,"POST","/admin/products",input);assertEquals(201,r.getResponse().getStatus(),r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));var p=data(r);ids.add(p.path("id").asText());return p;}
    private Map<String,Object> write(JsonNode p){return new HashMap<>(Map.of("title",p.path("title").asText(),"brand",p.path("brand").asText(),"category",p.path("category").asText(),"description",p.path("description").asText(),"expectedVersion",p.path("version").asInt(),"skus",json.convertValue(p.path("skus"),List.class)));}
    private MvcResult publish(Client c,JsonNode p,boolean yes) throws Exception{return call(c,"PATCH","/admin/products/"+p.path("id").asText()+"/publication",Map.of("expectedVersion",p.path("version").asInt(),"published",yes));}
    @AfterEach void cleanup(){for(String id:ids){jdbc.update("DELETE FROM catalog_sku WHERE product_id=?",id);jdbc.update("DELETE FROM catalog_product WHERE id=?",id);}if(!sessionKeys.isEmpty())redis.delete(sessionKeys);}

    @Test void draftPublishUnpublishArchiveAndPublicProjection() throws Exception{
        var admin=staff(true);var p=create(admin,product(List.of(sku(code(),true),sku(code(),false))));String id=p.path("id").asText();
        assertEquals(404,call(null,"GET","/catalog/products/"+id,null).getResponse().getStatus());
        assertFalse(data(call(null,"GET","/catalog/products",null)).path("items").toString().contains(id));
        var published=publish(admin,p,true);assertEquals(200,published.getResponse().getStatus());p=data(published);
        var publicData=data(call(null,"GET","/catalog/products/"+id,null));
        assertEquals(Set.of("id","title","brand","category","description","skus","image","work"),json.convertValue(publicData,Map.class).keySet());
        assertEquals(1,publicData.path("skus").size());assertEquals(1280,publicData.path("skus").get(0).path("priceTwd").asInt());
        assertEquals(Set.of("id","label","priceTwd","available"),json.convertValue(publicData.path("skus").get(0),Map.class).keySet());
        assertTrue(data(call(null,"GET","/catalog/products",null)).path("items").toString().contains(id));
        assertEquals(409,call(admin,"PUT","/admin/products/"+id,write(p)).getResponse().getStatus());
        assertEquals(409,call(admin,"DELETE","/admin/products/"+id+"?expectedVersion="+p.path("version").asInt(),null).getResponse().getStatus());
        p=data(publish(admin,p,false));assertEquals(404,call(null,"GET","/catalog/products/"+id,null).getResponse().getStatus());
        assertEquals(200,call(admin,"DELETE","/admin/products/"+id+"?expectedVersion="+p.path("version").asInt(),null).getResponse().getStatus());
        p=data(call(admin,"GET","/admin/products/"+id,null));assertEquals("ARCHIVED",p.path("status").asText());
        assertEquals(409,publish(admin,p,true).getResponse().getStatus());assertEquals(409,call(admin,"PUT","/admin/products/"+id,write(p)).getResponse().getStatus());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM catalog_sku WHERE product_id=?",Integer.class,id));
    }
    @Test void staffCanEditButCannotPublishAndPermissionsAreFresh() throws Exception{
        var employee=staff(false);var p=create(employee,product(List.of(sku(code(),true))));String id=p.path("id").asText();
        assertEquals(200,call(employee,"GET","/admin/products/"+id,null).getResponse().getStatus());
        assertEquals(403,publish(employee,p,true).getResponse().getStatus());
        var body=write(p);body.put("title","員工已編輯");var saved=call(employee,"PUT","/admin/products/"+id,body);assertEquals(200,saved.getResponse().getStatus(),saved.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        // Remove this permission only from isolated demo role, restore even on failure.
        jdbc.update("DELETE FROM role_permission WHERE role_id=2 AND permission_id=13");
        try{assertEquals(403,call(employee,"PUT","/admin/products/"+id,body).getResponse().getStatus());assertEquals(200,call(employee,"GET","/admin/products/"+id,null).getResponse().getStatus());}
        finally{jdbc.update("INSERT INTO role_permission(role_id,permission_id) VALUES(2,13)");}
        assertEquals(401,call(guest(),"GET","/admin/products",null).getResponse().getStatus());
        assertEquals(401,call(guest(),"POST","/catalog/products",body).getResponse().getStatus());
        assertEquals(403,call(employee,"POST","/admin/products/"+id,body).getResponse().getStatus());
    }
    @Test void validationCsrfMoneyAndMassAssignment() throws Exception{
        var admin=staff(true);var valid=product(List.of(sku(code(),true)));
        var noCsrf=new Client("wrong",admin.cookies());assertEquals(403,call(noCsrf,"POST","/admin/products",valid).getResponse().getStatus());
        for(var field:List.of("status","version","id")){var body=new HashMap<>(valid);body.put(field,"PUBLISHED");assertEquals(400,call(admin,"POST","/admin/products",body).getResponse().getStatus());}
        var invalid=sku(code(),true);invalid.put("priceTwd",1280.9);assertEquals(400,call(admin,"POST","/admin/products",product(List.of(invalid))).getResponse().getStatus());
        invalid.put("priceTwd",0);assertEquals(400,call(admin,"POST","/admin/products",product(List.of(invalid))).getResponse().getStatus());
        invalid.put("priceTwd",1280);invalid.put("stock",999);assertEquals(400,call(admin,"POST","/admin/products",product(List.of(invalid))).getResponse().getStatus());
        assertEquals(400,call(null,"GET","/catalog/products?page=0",null).getResponse().getStatus());
        assertEquals(400,call(null,"GET","/catalog/products?pageSize=51",null).getResponse().getStatus());
        assertEquals(400,call(null,"GET","/catalog/products?page=bad",null).getResponse().getStatus());
    }
    @Test void skuIdentityOwnershipAndTransactionalRollback() throws Exception{
        var admin=staff(true);String reserved=code();var p=create(admin,product(List.of(sku(reserved,true))));var q=create(admin,product(List.of(sku(code(),true))));String id=p.path("id").asText();
        var body=write(p);body.put("title","不可提交的更新");var all=new ArrayList<Map<String,Object>>(json.convertValue(p.path("skus"),List.class));all.add(sku(q.path("skus").get(0).path("code").asText(),true));body.put("skus",all);
        assertEquals(409,call(admin,"PUT","/admin/products/"+id,body).getResponse().getStatus());
        var unchanged=data(call(admin,"GET","/admin/products/"+id,null));assertEquals(p,unchanged);
        var foreign=sku(code(),true);foreign.put("id",q.path("skus").get(0).path("id").asText());body.put("skus",List.of(foreign));assertEquals(400,call(admin,"PUT","/admin/products/"+id,body).getResponse().getStatus());
        body.put("skus",List.of(sku(code(),true)));assertEquals(400,call(admin,"PUT","/admin/products/"+id,body).getResponse().getStatus());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM catalog_sku WHERE product_id=?",Integer.class,id));
        var change=json.convertValue(p.path("skus").get(0),Map.class);change.put("code",code());body.put("skus",List.of(change));assertEquals(400,call(admin,"PUT","/admin/products/"+id,body).getResponse().getStatus());
        String failedTitle="failed "+UUID.randomUUID();var fail=product(List.of(sku(code(),true),sku(reserved.toLowerCase(Locale.ROOT),true)));fail.put("title",failedTitle);
        assertEquals(409,call(admin,"POST","/admin/products",fail).getResponse().getStatus());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product WHERE title=?",Integer.class,failedTitle));
    }
    @Test void concurrentEditsAndPublishingHaveOneVersionWinner() throws Exception{
        var admin=staff(true);var p=create(admin,product(List.of(sku(code(),true))));String id=p.path("id").asText();var body=write(p);
        var statuses=race(()->call(admin,"PUT","/admin/products/"+id,body).getResponse().getStatus());assertEquals(Set.of(200,409),new HashSet<>(statuses));
        p=data(call(admin,"GET","/admin/products/"+id,null));assertEquals(1,p.path("version").asInt());final var latest=p;
        statuses=race(()->publish(admin,latest,true).getResponse().getStatus());assertEquals(Set.of(200,409),new HashSet<>(statuses));
        assertEquals(2,data(call(admin,"GET","/admin/products/"+id,null)).path("version").asInt());
        assertEquals(409,publish(admin,latest,false).getResponse().getStatus());
    }
    @Test void concurrentGlobalSkuCodeHasOneWinnerAndNoOrphans() throws Exception{
        var admin=staff(true);String same=code(),title="race "+UUID.randomUUID();var body=product(List.of(sku(same,true)));body.put("title",title);
        var statuses=race(()->{var r=call(admin,"POST","/admin/products",body);if(r.getResponse().getStatus()==201)ids.add(data(r).path("id").asText());return r.getResponse().getStatus();});
        assertEquals(Set.of(201,409),new HashSet<>(statuses));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product WHERE title=?",Integer.class,title));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM catalog_sku WHERE code=?",Integer.class,same.toUpperCase(Locale.ROOT)));
    }
    @Test void publishingRequiresEnabledSkuAndPaginationIsStable() throws Exception{
        var admin=staff(true);var disabled=create(admin,product(List.of(sku(code(),false))));assertEquals(409,publish(admin,disabled,true).getResponse().getStatus());
        for(int i=0;i<3;i++){var p=create(admin,product(List.of(sku(code(),true))));assertEquals(200,publish(admin,p,true).getResponse().getStatus());}
        var first=data(call(null,"GET","/catalog/products?page=1&pageSize=2",null));var second=data(call(null,"GET","/catalog/products?page=2&pageSize=2",null));
        assertEquals(3,first.path("total").asInt());assertEquals(2,first.path("items").size());assertEquals(1,second.path("items").size());
        assertFalse(first.path("items").toString().contains(second.path("items").get(0).path("id").asText()));
    }
    private List<Integer> race(Callable<Integer> call) throws Exception{var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var a=pool.submit(()->{start.await();return call.call();});var b=pool.submit(()->{start.await();return call.call();});start.countDown();return List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
}
