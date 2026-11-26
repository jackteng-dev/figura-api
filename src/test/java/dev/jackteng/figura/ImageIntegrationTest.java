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
@EnabledIfEnvironmentVariable(named="SHOP_IMAGES_INTEGRATION",matches="true")
class ImageIntegrationTest {
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
        var b=request(org.springframework.http.HttpMethod.valueOf(method),"/figura/api"+path).contextPath("/figura").servletPath("/api"+path.split("\\?",2)[0]);
        if(c!=null){b.cookie(c.cookies().toArray(Cookie[]::new));if(!method.equals("GET"))b.header("X-XSRF-TOKEN",c.csrf());}
        if(body!=null)b.contentType("application/json").content(json.writeValueAsBytes(body));
        return mvc.perform(b).andReturn();
    }
    private Client guest() throws Exception{var r=call(null,"GET","/auth/csrf",null);return new Client(data(r).path("token").asText(),List.of(cookie(r,"XSRF-TOKEN")));}
    private Client staff(boolean admin) throws Exception{
        var c=guest();String device=UUID.randomUUID().toString(),name=admin?"shop_admin":"shop_staff";
        var r=StaffLogin.complete(mvc, () -> mvc.perform(post("/figura/api/admin/login").contextPath("/figura").servletPath("/api/admin/login").cookie(c.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN",c.csrf()).param("username",name).param("password",System.getenv(admin?"DEMO_ADMIN_PASSWORD":"DEMO_STAFF_PASSWORD")).param("deviceId",device)).andReturn());assertEquals(200,r.getResponse().getStatus());
        sessionKeys.add("shop:session:"+data(r).path("id").asInt()+":"+device);
        return new Client(c.csrf(),List.of(c.cookies().get(0),cookie(r,"accessToken"),cookie(r,"refreshToken")));
    }
    private Map<String,Object> sku(String code,boolean enabled){return new HashMap<>(Map.of("code",code,"label","初回限定款","priceTwd",1280,"enabled",enabled));}
    private Map<String,Object> product(List<Map<String,Object>> skus){return new HashMap<>(Map.of("title","integration collection "+UUID.randomUUID(),"brand","ELPSY","category","公仔","description","<script>資料是文字</script>","expectedVersion",0,"skus",skus));}
    private String code(){return "TEST-"+UUID.randomUUID();}
    private JsonNode create(Client c,Map<String,Object> input) throws Exception{var r=call(c,"POST","/admin/products",input);assertEquals(201,r.getResponse().getStatus(),r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));var p=data(r);ids.add(p.path("id").asText());return p;}
    private Map<String,Object> write(JsonNode p){return new HashMap<>(Map.of("title",p.path("title").asText(),"brand",p.path("brand").asText(),"category",p.path("category").asText(),"description",p.path("description").asText(),"expectedVersion",p.path("version").asInt(),"skus",json.convertValue(p.path("skus"),List.class)));}
    private MvcResult publish(Client c,JsonNode p,boolean yes) throws Exception{return call(c,"PATCH","/admin/products/"+p.path("id").asText()+"/publication",Map.of("expectedVersion",p.path("version").asInt(),"published",yes));}
    @AfterEach void cleanup(){org.mockito.Mockito.reset(images,store);for(String id:ids){jdbc.update("UPDATE catalog_product SET status='ARCHIVED' WHERE id=?",id);}if(!sessionKeys.isEmpty())redis.delete(sessionKeys);}


    @org.springframework.boot.test.mock.mockito.SpyBean dev.jackteng.figura.mapper.ImageMapper images;
    @org.springframework.boot.test.mock.mockito.SpyBean dev.jackteng.figura.service.ImageObjectStore store;
    @Autowired dev.jackteng.figura.service.ImageCleanupService collector;
    private byte[] png(int w,int h,int color) throws Exception{var image=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,color);var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",out);return out.toByteArray();}
    private String path(JsonNode p){return "/admin/products/"+p.path("id").asText()+"/image";}
    private JsonNode fresh(Client c,JsonNode p) throws Exception{return data(call(c,"GET","/admin/products/"+p.path("id").asText(),null));}
    private Map<String,Object> input(JsonNode p){return new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",p.path("version").asInt(),"alt","合成主圖 <img src=x>"));}
    private MvcResult upload(Client c,JsonNode p,Map<String,Object> input,byte[] bytes,String type) throws Exception{
        var b=multipart("/figura/api"+path(p)).file(new org.springframework.mock.web.MockMultipartFile("file","../../secret.svg",type,bytes));b.contextPath("/figura").servletPath("/api"+path(p));
        input.forEach((k,v)->b.param(k,v.toString()));if(c!=null)b.cookie(c.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN",c.csrf());return mvc.perform(b).andReturn();
    }
    private MvcResult upload(Client c,JsonNode p,Map<String,Object> input) throws Exception{return upload(c,p,input,png(24,18,0xff5533aa),"image/png");}
    private int events(JsonNode p){return jdbc.queryForObject("SELECT COUNT(*) FROM product_image_event WHERE product_id=?",Integer.class,p.path("id").asText());}
    private void dueOwn(){for(String id:ids)jdbc.update("UPDATE product_image_cleanup SET not_before=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE object_key LIKE ?","products/"+id+"/%");}
    private JsonNode fixture(Client c) throws Exception{return create(c,product(List.of(sku(code(),true))));}
    @Test void privateUploadPublishedProjectionAndDownpublishContentGuard() throws Exception{
        var c=staff(false);var a=staff(true);var p=fixture(c);var r=upload(c,p,input(p));assertEquals(200,r.getResponse().getStatus(),r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));p=fresh(c,p);assertEquals(1,p.path("version").asInt());String id=p.path("image").path("id").asText();var content=call(c,"GET",path(p)+"/"+id,null);assertEquals(200,content.getResponse().getStatus());assertEquals("image/png",content.getResponse().getContentType());assertEquals("no-store",content.getResponse().getHeader("Cache-Control"));assertEquals("nosniff",content.getResponse().getHeader("X-Content-Type-Options"));assertEquals(24,javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(content.getResponse().getContentAsByteArray())).getWidth());String pub="/catalog/products/"+p.path("id").asText()+"/image/"+id;assertEquals(404,call(null,"GET",pub,null).getResponse().getStatus());p=data(publish(a,p,true));var visible=data(call(null,"GET","/catalog/products/"+p.path("id").asText(),null));assertEquals(Set.of("id","url","alt","width","height"),json.convertValue(visible.path("image"),Map.class).keySet());assertFalse(visible.toString().contains("objectKey"));assertEquals(200,call(null,"GET",pub,null).getResponse().getStatus());p=data(publish(a,p,false));assertEquals(404,call(null,"GET",pub,null).getResponse().getStatus());assertEquals(200,call(c,"GET",path(p)+"/"+id,null).getResponse().getStatus());
    }
    @Test void uploadReplayStrictContentAndRetiredUrlAfterReplacement() throws Exception{
        var c=staff(false);var p=fixture(c);var first=input(p);assertEquals(200,upload(c,p,first).getResponse().getStatus());var old=fresh(c,p);assertTrue(data(upload(c,p,first)).path("replayed").asBoolean());assertEquals(409,upload(c,p,first,png(24,18,0xff000000),"image/png").getResponse().getStatus());var changed=new HashMap<>(first);changed.put("alt","different");assertEquals(409,upload(c,p,changed).getResponse().getStatus());assertEquals(1,events(p));assertEquals(200,upload(c,old,input(old)).getResponse().getStatus());var current=fresh(c,p);assertNotEquals(old.path("image").path("id"),current.path("image").path("id"));assertEquals(404,call(c,"GET",old.path("image").path("url").asText().substring(4),null).getResponse().getStatus());assertTrue(data(upload(c,p,first)).path("replayed").asBoolean());assertEquals(current.path("image"),data(upload(c,p,first)).path("current").path("image"));
    }

    @Test void removeReplayQueuesOldObjectAndCleanupDeletesOnlyUnreferenced() throws Exception{
        var c=staff(false);var p=fixture(c);upload(c,p,input(p));p=fresh(c,p);var asset=images.cover(p.path("id").asText());var request=input(p);request.remove("alt");assertEquals(200,call(c,"DELETE",path(p),request).getResponse().getStatus());assertTrue(fresh(c,p).path("image").isNull());assertTrue(data(call(c,"DELETE",path(p),request)).path("replayed").asBoolean());assertEquals(2,events(p));assertNotNull(store.get(asset.getObjectKey()));dueOwn();collector.run();assertThrows(RuntimeException.class,()->store.get(asset.getObjectKey()));assertEquals(2,events(p));
    }
    @Test void invalidImagesUnknownFieldsEmptyAltAndVersionDoNotMutate() throws Exception{
        var c=staff(false);var p=fixture(c);var input=input(p);for(String key:List.of("owner","objectKey","amountTwd","actorId")){var bad=new HashMap<>(input);bad.put(key,"forged");assertEquals(400,upload(c,p,bad).getResponse().getStatus());}for(String alt:List.of(" ","a".repeat(161))){var bad=new HashMap<>(input);bad.put("alt",alt);assertEquals(400,upload(c,p,bad).getResponse().getStatus());}var bad=new HashMap<>(input);bad.put("expectedVersion","0.5");assertEquals(400,upload(c,p,bad).getResponse().getStatus());assertEquals(400,upload(c,p,input,"<svg onload=alert(1)>".getBytes(),"image/svg+xml").getResponse().getStatus());assertEquals(400,upload(c,p,input,new byte[0],"image/png").getResponse().getStatus());assertEquals(400,upload(c,p,input,png(4097,1,0xff000000),"image/png").getResponse().getStatus());assertEquals(400,upload(c,p,input,png(1,1,0xff000000),"image/jpeg").getResponse().getStatus());assertEquals(0,events(p));assertEquals(p,fresh(c,p));
    }
    @Test void versionPublishedArchivedAndForeignContentAreGuarded() throws Exception{
        var c=staff(false);var a=staff(true);var p=fixture(c);upload(c,p,input(p));var current=fresh(c,p);assertEquals(409,upload(c,p,input(p)).getResponse().getStatus());var other=fixture(c);assertEquals(404,call(c,"GET",path(other)+"/"+current.path("image").path("id").asText(),null).getResponse().getStatus());current=data(publish(a,current,true));assertEquals(409,upload(c,current,input(current)).getResponse().getStatus());current=data(publish(a,current,false));assertEquals(200,call(c,"DELETE","/admin/products/"+current.path("id").asText()+"?expectedVersion="+current.path("version").asInt(),null).getResponse().getStatus());current=fresh(c,p);assertEquals(409,upload(c,current,input(current)).getResponse().getStatus());assertEquals(1,events(p));
    }
    @Test void csrfAndUnauthenticatedImageWritesAreDenied() throws Exception{
        var c=staff(false);var p=fixture(c);assertEquals(401,upload(guest(),p,input(p)).getResponse().getStatus());assertEquals(403,upload(new Client("wrong",c.cookies()),p,input(p)).getResponse().getStatus());assertEquals(401,call(null,"GET",path(p),null).getResponse().getStatus());assertEquals(403,call(c,"PUT",path(p),input(p)).getResponse().getStatus());assertEquals(0,events(p));
    }
    @Test void auditFailureRollsBackCoverVersionButDurableOrphanReservationSurvives() throws Exception{
        var c=staff(false);var p=fixture(c);var input=input(p);org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic audit failure")).when(images).append(org.mockito.ArgumentMatchers.any());assertEquals(500,upload(c,p,input).getResponse().getStatus());org.mockito.Mockito.reset(images);assertEquals(p,fresh(c,p));assertEquals(0,events(p));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM product_image_cleanup WHERE object_key LIKE ?",Integer.class,"products/"+p.path("id").asText()+"/%"));dueOwn();collector.run();assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM product_image_cleanup WHERE object_key LIKE ?",Integer.class,"products/"+p.path("id").asText()+"/%"));assertEquals(200,upload(c,p,input).getResponse().getStatus());assertEquals(1,events(p));
    }
    @Test void storageFailureHasNoDbMutationAndSameOperationRetries() throws Exception{
        var c=staff(false);var p=fixture(c);var input=input(p);org.mockito.Mockito.doThrow(new RuntimeException("synthetic S3 failure")).when(store).put(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());assertEquals(503,upload(c,p,input).getResponse().getStatus());org.mockito.Mockito.reset(store);assertEquals(p,fresh(c,p));dueOwn();collector.run();assertEquals(200,upload(c,p,input).getResponse().getStatus());assertEquals(1,events(p));
    }
    @Test void deleteFailureRetainsRetryQueueAndNeverDeletesActiveCover() throws Exception{
        var c=staff(false);var p=fixture(c);upload(c,p,input(p));p=fresh(c,p);var active=images.cover(p.path("id").asText());images.queue(active.getObjectKey());dueOwn();collector.run();assertNotNull(store.get(active.getObjectKey()));assertEquals(0,images.referenced("not-an-object"));var remove=input(p);remove.remove("alt");call(c,"DELETE",path(p),remove);org.mockito.Mockito.doThrow(new RuntimeException("synthetic cleanup failure")).when(store).delete(org.mockito.ArgumentMatchers.anyString());dueOwn();collector.run();assertEquals(1,jdbc.queryForObject("SELECT attempts FROM product_image_cleanup WHERE object_key=?",Integer.class,active.getObjectKey()));org.mockito.Mockito.reset(store);dueOwn();collector.run();assertThrows(RuntimeException.class,()->store.get(active.getObjectKey()));
    }
    @Test void sameUploadRaceReplaysOnceAndDifferentUploadRaceHasOneWinner() throws Exception{
        var c=staff(false);var p=fixture(c);var input=input(p);assertEquals(List.of(200,200),race(()->upload(c,p,input).getResponse().getStatus(),()->upload(c,p,input).getResponse().getStatus()));assertEquals(1,events(p));var other=fixture(c);var one=input(other);var two=input(other);assertEquals(Set.of(200,409),new HashSet<>(race(()->upload(c,other,one).getResponse().getStatus(),()->upload(c,other,two).getResponse().getStatus())));assertEquals(1,events(other));
    }
    @Test void crossActorGlobalRequestCollisionRollsBackLosingAssetAndVersion() throws Exception{
        var c=staff(false);var a=staff(true);var one=fixture(c);var two=fixture(a);var first=input(one);var second=input(two);second.put("requestId",first.get("requestId"));assertEquals(Set.of(200,409),new HashSet<>(race(()->upload(c,one,first).getResponse().getStatus(),()->upload(a,two,second).getResponse().getStatus())));assertEquals(1,events(one)+events(two));assertEquals(1,fresh(c,one).path("version").asInt()+fresh(a,two).path("version").asInt());dueOwn();collector.run();
    }
    @Test void liveGrantRevocationAndStoppedActorPreventWrites() throws Exception{
        var c=staff(false);var p=fixture(c);jdbc.update("DELETE FROM role_permission WHERE role_id=2 AND permission_id=56");try{assertEquals(403,upload(c,p,input(p)).getResponse().getStatus());}finally{jdbc.update("INSERT INTO role_permission VALUES(2,56)");}assertEquals(0,events(p));assertEquals(MigrationCount.files(),jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class));
    }
    private List<Integer> race(Callable<Integer> a,Callable<Integer> b) throws Exception{var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var one=pool.submit(()->{start.await();return a.call();});var two=pool.submit(()->{start.await();return b.call();});start.countDown();return List.of(one.get(30,TimeUnit.SECONDS),two.get(30,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
    private JsonNode gallery(Client c,JsonNode p) throws Exception{return data(call(c,"GET",path(p)+"?gallery=true",null));}
    private Map<String,Object> addInput(Client c,JsonNode p) throws Exception{var body=input(p);body.put("expectedActorId",data(call(c,"GET","/auth/me",null)).path("id").asInt());body.put("operation","ADD");body.put("type","DETAIL");return body;}
    private JsonNode imageCommand(Client c,JsonNode p,String action,String image,List<String> order,int expected) throws Exception{
        var body=new HashMap<String,Object>();body.put("requestId",UUID.randomUUID().toString());body.put("expectedVersion",p.path("version").asInt());body.put("action",action);body.put("expectedActorId",data(call(c,"GET","/auth/me",null)).path("id").asInt());if(image!=null)body.put("imageId",image);if(order!=null)body.put("imageIds",order);
        var r=call(c,action.equals("REMOVE")?"DELETE":"POST",path(p)+(action.equals("REMOVE")?"?gallery=true":""),body);assertEquals(expected,r.getResponse().getStatus(),r.getResponse().getContentAsString());return data(r);
    }
    @Test void galleryLegacyCompatibilityResponsiveImagesAndPublicationGuard() throws Exception{
        var c=staff(true);var p=fixture(c);
        assertEquals(200,upload(c,p,input(p)).getResponse().getStatus());p=fresh(c,p);String legacy=p.path("image").path("id").asText();
        var body=addInput(c,p);assertEquals(200,upload(c,p,body,png(960,960,0xff778899),"image/png").getResponse().getStatus());p=fresh(c,p);
        assertEquals(legacy,p.path("image").path("id").asText());var g=gallery(c,p);assertEquals(2,g.path("images").size());var next=g.path("images").get(1);String image=next.path("id").asText();assertEquals(2,next.path("variants").size());assertFalse(next.has("objectKey"));
        assertEquals(404,call(null,"GET","/catalog/products/"+p.path("id").asText()+"/image",null).getResponse().getStatus());
        p=data(publish(c,p,true));var pub=call(null,"GET","/catalog/products/"+p.path("id").asText()+"/image",null);assertEquals(200,pub.getResponse().getStatus());assertEquals(2,data(pub).size());
        String content="/catalog/products/"+p.path("id").asText()+"/image/"+image;var small=call(null,"GET",content+"?width=320",null);assertEquals(200,small.getResponse().getStatus());assertEquals(320,javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(small.getResponse().getContentAsByteArray())).getWidth());assertEquals("no-store",small.getResponse().getHeader("Cache-Control"));assertEquals(404,call(null,"GET",content+"?width=999",null).getResponse().getStatus());
        assertEquals(409,upload(c,p,addInput(c,p)).getResponse().getStatus());p=data(publish(c,p,false));assertEquals(404,call(null,"GET",content,null).getResponse().getStatus());
        imageCommand(c,p,"COVER",image,null,200);p=fresh(c,p);assertEquals(image,p.path("image").path("id").asText());imageCommand(c,p,"REORDER",null,List.of(image,legacy),200);p=fresh(c,p);assertEquals(image,gallery(c,p).path("images").get(0).path("id").asText());
        imageCommand(c,p,"REMOVE",image,null,200);p=fresh(c,p);assertEquals(legacy,p.path("image").path("id").asText());assertEquals(404,call(c,"GET",path(p)+"/"+image,null).getResponse().getStatus());
        assertEquals(200,upload(c,p,input(p)).getResponse().getStatus());p=fresh(c,p);assertEquals(1,gallery(c,p).path("images").size());assertEquals(404,call(c,"GET",path(p)+"/"+legacy,null).getResponse().getStatus());
    }
    @Test void galleryIdempotencyLimitReplacementAndInvalidCommands() throws Exception{
        var c=staff(true);var p=fixture(c);var first=addInput(c,p);assertEquals(200,upload(c,p,first).getResponse().getStatus());var replay=upload(c,p,first);assertEquals(200,replay.getResponse().getStatus());assertTrue(data(replay).path("replayed").asBoolean());
        first.put("type","FACE");assertEquals(409,upload(c,p,first).getResponse().getStatus());p=fresh(c,p);
        for(int n=1;n<6;n++){assertEquals(200,upload(c,p,addInput(c,p)).getResponse().getStatus());p=fresh(c,p);}
        assertEquals(409,upload(c,p,addInput(c,p)).getResponse().getStatus());var g=gallery(c,p);String image=g.path("images").get(0).path("id").asText();var replacement=addInput(c,p);replacement.put("operation","REPLACE");replacement.put("imageId",image);assertEquals(200,upload(c,p,replacement).getResponse().getStatus());p=fresh(c,p);assertEquals(6,gallery(c,p).path("images").size());assertEquals(404,call(c,"GET",path(p)+"/"+image,null).getResponse().getStatus());
        imageCommand(c,p,"REORDER",null,List.of(p.path("image").path("id").asText()),409);imageCommand(c,p,"COVER",UUID.randomUUID().toString(),null,404);
        var other=fixture(c);var collision=addInput(c,other);collision.put("requestId",replacement.get("requestId"));assertEquals(409,upload(c,other,collision).getResponse().getStatus());
        var legacy=input(other);legacy.put("requestId",replacement.get("requestId"));assertEquals(409,upload(c,other,legacy).getResponse().getStatus());
        var invalid=addInput(c,other);invalid.put("type","OTHER");assertEquals(400,upload(c,other,invalid).getResponse().getStatus());
    }
    @org.springframework.boot.test.mock.mockito.SpyBean dev.jackteng.figura.mapper.GalleryMapper galleryMapper;
    @Test void galleryAuditFailureRollsBackAndRemoveChecksLivePermission() throws Exception{
        var c=staff(false);var p=fixture(c);var body=addInput(c,p);
        org.mockito.Mockito.doThrow(new RuntimeException("synthetic audit failure")).when(galleryMapper).append(org.mockito.ArgumentMatchers.any());
        try{assertEquals(500,upload(c,p,body).getResponse().getStatus());}finally{org.mockito.Mockito.reset(galleryMapper);}
        assertEquals(0,fresh(c,p).path("version").asInt());assertEquals(0,gallery(c,p).path("images").size());assertEquals(200,upload(c,p,body).getResponse().getStatus());p=fresh(c,p);String image=p.path("image").path("id").asText();
        jdbc.update("DELETE FROM role_permission WHERE role_id=2 AND permission_id=57");try{imageCommand(c,p,"REMOVE",image,null,403);}finally{jdbc.update("INSERT INTO role_permission VALUES(2,57)");}
        assertEquals(1,gallery(c,p).path("images").size());var missingCsrf=mvc.perform(delete("/figura/api"+path(p)+"?gallery=true").contextPath("/figura").servletPath("/api"+path(p)).cookie(c.cookies().toArray(Cookie[]::new)).contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",p.path("version").asInt(),"action","REMOVE","imageId",image)))).andReturn();assertEquals(403,missingCsrf.getResponse().getStatus());
        dueOwn();collector.run();assertEquals(200,call(c,"GET",path(p)+"/"+image,null).getResponse().getStatus());
    }
    @Test void galleryConcurrentAddAndLegacyRequestCollisionHaveOneWinner() throws Exception{
        var c=staff(true);var p=fixture(c);var one=addInput(c,p);var two=addInput(c,p);assertEquals(Set.of(200,409),new HashSet<>(race(()->upload(c,p,one).getResponse().getStatus(),()->upload(c,p,two).getResponse().getStatus())));assertEquals(1,gallery(c,p).path("images").size());
        var a=fixture(c);var b=fixture(c);var legacy=input(a);var modern=addInput(c,b);modern.put("requestId",legacy.get("requestId"));assertEquals(Set.of(200,409),new HashSet<>(race(()->upload(c,a,legacy).getResponse().getStatus(),()->upload(c,b,modern).getResponse().getStatus())));
    }

    @Test void galleryActorPreconditionAndLegacyRemovePromotion() throws Exception{
        var a=staff(true);var b=staff(false);var p=fixture(a);var body=addInput(a,p);
        assertEquals(409,upload(b,p,body).getResponse().getStatus());assertEquals(0,gallery(a,p).path("images").size());
        assertEquals(200,upload(a,p,body).getResponse().getStatus());p=fresh(a,p);assertEquals(200,upload(a,p,addInput(a,p)).getResponse().getStatus());p=fresh(a,p);
        var g=gallery(a,p);String next=g.path("images").get(1).path("id").asText();var remove=Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",p.path("version").asInt());
        assertEquals(200,call(a,"DELETE",path(p),remove).getResponse().getStatus());p=fresh(a,p);assertEquals(next,p.path("image").path("id").asText());assertEquals(1,gallery(a,p).path("images").size());
        var wrong=new HashMap<String,Object>(Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",p.path("version").asInt(),"expectedActorId",data(call(b,"GET","/auth/me",null)).path("id").asInt(),"action","COVER","imageId",next));assertEquals(409,call(a,"POST",path(p),wrong).getResponse().getStatus());
    }

}
