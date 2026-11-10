package dev.jackteng.figura;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.figura.utils.JwtUtil;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc(print=MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named="SHOP_MYSQL_INTEGRATION",matches="true")
class MobileIdentityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtUtil jwt;
    final Set<String> ids=ConcurrentHashMap.newKeySet();
    static final String PASSWORD="synthetic-mobile-password-2026";
    MvcResult call(String method,String path,String token,Object body,Cookie... cookies)throws Exception {
        var b=request(org.springframework.http.HttpMethod.valueOf(method),"/figura/api"+path)
                .contextPath("/figura").servletPath("/api"+path);
        if(token!=null)b.header("Authorization","Bearer "+token);
        if(body!=null)b.contentType("application/json").content(json.writeValueAsBytes(body));
        if(cookies.length>0)b.cookie(cookies);
        return mvc.perform(b).andReturn();
    }
    JsonNode data(MvcResult r)throws Exception{return json.readTree(r.getResponse().getContentAsByteArray()).path("data");}
    JsonNode login(String username,String device)throws Exception {
        var r=call("POST","/mobile/customer/auth/login",null,Map.of("username",username,"password",PASSWORD,"deviceId",device,"rememberMe",false));
        assertEquals(200,r.getResponse().getStatus());assertEquals("no-store",r.getResponse().getHeader("Cache-Control"));
        assertNull(r.getResponse().getHeader("Set-Cookie"));return data(r);
    }
    String register()throws Exception {
        String name="mobile_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        var r=call("POST","/mobile/customer/auth/register",null,Map.of("username",name,"password",PASSWORD,"displayName","App 測試會員"));
        assertEquals(201,r.getResponse().getStatus());ids.add(data(r).path("id").asText());return name;
    }
    @AfterEach void cleanup(){for(String id:ids){jdbc.update("DELETE FROM customer WHERE id=?",id);for(String realm:List.of("shop:customer:session:","shop:customer:mobile:session:")){var keys=redis.keys(realm+id+":*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);}}}
    @Test void cookieOnlyNeverAuthenticatesNativeAndNativeTokenDoesNotOpenWebsite()throws Exception {
        String name=register(),device=UUID.randomUUID().toString();var tokens=login(name,device);String access=tokens.path("accessToken").asText();
        assertEquals(200,call("GET","/mobile/customer/me",access,null).getResponse().getStatus());
        assertEquals(401,call("GET","/mobile/customer/me",null,null,new Cookie("customerAccessToken",access)).getResponse().getStatus());
        assertEquals(401,call("GET","/customer/me",access,null,new Cookie("customerAccessToken",access)).getResponse().getStatus());
        var claims=Map.<String,Object>of("id",tokens.path("customer").path("id").asText(),"deviceId",device,"jti",UUID.randomUUID().toString(),"realm","customer");
        assertEquals(401,call("GET","/mobile/customer/me",jwt.genAccessToken(claims),null).getResponse().getStatus());
        assertEquals(401,call("GET","/mobile/customer/me",jwt.genAccessToken(Map.of("id",1,"realm","staff")),null).getResponse().getStatus());
        assertEquals(403,call("GET","/mobile/customer/staff",access,null).getResponse().getStatus());
        assertEquals(403,call("POST","/customer/auth/login",access,Map.of()).getResponse().getStatus());
        assertEquals(401,call("GET","/mobile/customer/auth/login",null,null).getResponse().getStatus());
    }
    @Test void ownerComesFromPrincipalAndProfileMassAssignmentRejected()throws Exception {
        String a=register(),b=register();var t=login(a,UUID.randomUUID().toString());String access=t.path("accessToken").asText();
        var other=login(b,UUID.randomUUID().toString());String id=other.path("customer").path("id").asText();
        var r=call("PATCH","/mobile/customer/me",access,Map.of("displayName","新名稱","id",id));assertEquals(400,r.getResponse().getStatus());
        assertEquals(200,call("PATCH","/mobile/customer/me",access,Map.of("displayName","新名稱")).getResponse().getStatus());
        assertEquals("新名稱",data(call("GET","/mobile/customer/me",access,null)).path("displayName").asText());
        assertEquals("App 測試會員",data(call("GET","/mobile/customer/me",other.path("accessToken").asText(),null)).path("displayName").asText());
    }
    @Test void refreshRaceOneWinnerOldAccessAndRefreshStayRevoked()throws Exception {
        String name=register();var t=login(name,UUID.randomUUID().toString());var pool=Executors.newFixedThreadPool(4);var start=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<MvcResult>>();for(int i=0;i<4;i++)futures.add(pool.submit(()->{start.await();return call("POST","/mobile/customer/auth/refresh",null,Map.of("refreshToken",t.path("refreshToken").asText()));}));start.countDown();
            var winners=new ArrayList<JsonNode>();for(var f:futures){var r=f.get(20,TimeUnit.SECONDS);if(r.getResponse().getStatus()==200)winners.add(data(r));else assertEquals(401,r.getResponse().getStatus());}
            assertEquals(1,winners.size());var next=winners.get(0);
            assertEquals(401,call("GET","/mobile/customer/me",t.path("accessToken").asText(),null).getResponse().getStatus());
            assertEquals(401,call("POST","/mobile/customer/auth/refresh",null,Map.of("refreshToken",t.path("refreshToken").asText())).getResponse().getStatus());
            assertEquals(200,call("POST","/mobile/customer/auth/logout",null,Map.of("refreshToken",t.path("refreshToken").asText())).getResponse().getStatus());
            assertEquals(200,call("GET","/mobile/customer/me",next.path("accessToken").asText(),null).getResponse().getStatus());
        }finally{pool.shutdownNow();}
    }
    @Test void logoutOnlyRevokesItsOwnDeviceAndWebSessionRemains()throws Exception {
        String name=register(),device=UUID.randomUUID().toString();var t=login(name,device);var other=login(name,UUID.randomUUID().toString());
        String id=t.path("customer").path("id").asText(),jti=UUID.randomUUID().toString();
        redis.opsForValue().set("shop:customer:session:"+id+":"+device,jti);
        var web=jwt.genAccessToken(Map.of("id",id,"deviceId",device,"jti",jti,"realm","customer"));
        assertEquals(200,call("POST","/mobile/customer/auth/logout",null,Map.of("refreshToken",t.path("refreshToken").asText())).getResponse().getStatus());
        assertEquals(401,call("GET","/mobile/customer/me",t.path("accessToken").asText(),null).getResponse().getStatus());
        assertEquals(401,call("POST","/mobile/customer/auth/refresh",null,Map.of("refreshToken",t.path("refreshToken").asText())).getResponse().getStatus());
        assertEquals(200,call("GET","/mobile/customer/me",other.path("accessToken").asText(),null).getResponse().getStatus());
        assertEquals(200,call("GET","/customer/me",null,null,new Cookie("customerAccessToken",web)).getResponse().getStatus());
    }
    @Test void disabledAccountAndNewLoginInvalidateCurrentDevice()throws Exception {
        String name=register(),device=UUID.randomUUID().toString();var old=login(name,device);var t=login(name,device);
        assertEquals(401,call("GET","/mobile/customer/me",old.path("accessToken").asText(),null).getResponse().getStatus());
        jdbc.update("UPDATE customer SET enabled=FALSE WHERE id=?",t.path("customer").path("id").asText());
        assertEquals(401,call("GET","/mobile/customer/me",t.path("accessToken").asText(),null).getResponse().getStatus());
        assertEquals(401,call("POST","/mobile/customer/auth/refresh",null,Map.of("refreshToken",t.path("refreshToken").asText())).getResponse().getStatus());
    }
    @Test void malformedWrongTokenTypeAndExtraRefreshFieldsRejected()throws Exception {
        var t=login(register(),UUID.randomUUID().toString());
        assertEquals(401,call("GET","/mobile/customer/me",t.path("refreshToken").asText(),null).getResponse().getStatus());
        assertEquals(401,call("POST","/mobile/customer/auth/refresh",null,Map.of("refreshToken",t.path("accessToken").asText())).getResponse().getStatus());
        assertEquals(401,call("GET","/mobile/customer/me","broken",null).getResponse().getStatus());
        assertEquals(400,call("POST","/mobile/customer/auth/refresh",null,Map.of("refreshToken",t.path("refreshToken").asText(),"id","other")).getResponse().getStatus());
        assertEquals(400,call("POST","/mobile/customer/auth/login",null,Map.of("username","abc","password",PASSWORD,"deviceId","invalid")).getResponse().getStatus());
    }
}
