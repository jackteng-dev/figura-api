package com.shop.elpsy;

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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named="SHOP_MYSQL_INTEGRATION",matches="true")
class CustomerIdentityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired BCryptPasswordEncoder encoder;
    private final Set<String> ids=ConcurrentHashMap.newKeySet();
    private static final String PASSWORD="integration-test-password-2026";
    record Client(String csrf,List<Cookie> cookies) {}
    private MockHttpServletRequestBuilder path(MockHttpServletRequestBuilder builder,String path){return builder.contextPath("/ELPSY").servletPath("/api"+path);}
    private MvcResult call(Client client,String method,String path,Object body) throws Exception {
        var builder=path(request(org.springframework.http.HttpMethod.valueOf(method),"/ELPSY/api"+path),path);
        builder.cookie(client.cookies().toArray(Cookie[]::new));
        if(!method.equals("GET"))builder.header("X-XSRF-TOKEN",client.csrf());
        if(body!=null)builder.contentType("application/json").content(json.writeValueAsBytes(body));
        return mvc.perform(builder).andReturn();
    }
    private Client guest() throws Exception {
        var result=mvc.perform(path(get("/ELPSY/api/auth/csrf"),"/auth/csrf")).andReturn();assertEquals(200,result.getResponse().getStatus());
        return new Client(json.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data").path("token").asText(),List.of(cookie(result,"XSRF-TOKEN")));
    }
    private Cookie cookie(MvcResult result,String name){
        String header=result.getResponse().getHeaders("Set-Cookie").stream().filter(h->h.startsWith(name+"=")).findFirst().orElseThrow();
        return new Cookie(name,header.split(";",2)[0].substring(name.length()+1));
    }
    private String field(MvcResult result,String name) throws Exception {return json.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data").path(name).asText();}
    private String username(){return "test_"+UUID.randomUUID().toString().replace("-","").substring(0,20);}
    private MvcResult register(Client client,String name) throws Exception {
        var result=call(client,"POST","/customer/auth/register",Map.of("username",name,"password",PASSWORD,"displayName","展示會員"));
        if(result.getResponse().getStatus()==201)ids.add(field(result,"id"));return result;
    }
    private Client login(String name) throws Exception {
        var client=guest();var result=call(client,"POST","/customer/auth/login",Map.of("username",name,"password",PASSWORD,"deviceId",UUID.randomUUID().toString(),"rememberMe",false));assertEquals(200,result.getResponse().getStatus());
        return new Client(client.csrf(),List.of(client.cookies().get(0),cookie(result,"customerAccessToken"),cookie(result,"customerRefreshToken")));
    }
    private Client rotated(Client before,MvcResult result){return new Client(before.csrf(),List.of(before.cookies().get(0),cookie(result,"customerAccessToken"),cookie(result,"customerRefreshToken")));}
    @AfterEach void cleanup(){
        for(String id:ids){jdbc.update("DELETE FROM customer WHERE id=?",id);var keys=redis.keys("shop:customer:session:"+id+":*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);}
    }
    @Test void registrationValidationProjectionAndBcrypt() throws Exception {
        var guest=guest();String name=username();var result=register(guest,name);assertEquals(201,result.getResponse().getStatus());
        var data=json.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");assertEquals(Set.of("id","username","displayName","createdAt"),json.convertValue(data,Map.class).keySet());
        assertTrue(encoder.matches(PASSWORD,jdbc.queryForObject("SELECT password_hash FROM customer WHERE id=?",String.class,field(result,"id"))));
        assertEquals(409,register(guest,name.toUpperCase(Locale.ROOT)).getResponse().getStatus());
        assertEquals(400,call(guest,"POST","/customer/auth/register",Map.of("username",username(),"password",PASSWORD,"displayName","名稱","role","STAFF")).getResponse().getStatus());
        assertEquals(400,call(guest,"POST","/customer/auth/register",Map.of("username",username(),"password","short","displayName","名稱")).getResponse().getStatus());
        assertEquals(400,call(guest,"POST","/customer/auth/register",Map.of("username",username(),"password","測".repeat(25),"displayName","名稱")).getResponse().getStatus());
        assertEquals(401,call(guest,"GET","/customer/me",null).getResponse().getStatus());
        var missingCsrf=mvc.perform(path(post("/ELPSY/api/customer/auth/register"),"/customer/auth/register").contentType("application/json").content("{}")).andReturn();assertEquals(403,missingCsrf.getResponse().getStatus());
        assertEquals(410,call(guest,"POST","/user/register",Map.of()).getResponse().getStatus());
    }
    @Test void ownershipIgnoresQueryIdAndRejectsMassAssignment() throws Exception {
        String a=username(),b=username();var guest=guest();String idA=field(register(guest,a),"id"),idB=field(register(guest,b),"id");var client=login(a);
        var getOwn=mvc.perform(path(get("/ELPSY/api/customer/me"),"/customer/me").param("id",idB).cookie(client.cookies().toArray(Cookie[]::new))).andReturn();assertEquals(idA,field(getOwn,"id"));
        assertEquals(400,call(client,"PATCH","/customer/me",Map.of("displayName","竄改","id",idB)).getResponse().getStatus());
        assertEquals(400,call(client,"PATCH","/customer/me",Map.of("displayName","竄改","enabled",false)).getResponse().getStatus());
        assertEquals(400,call(client,"PATCH","/customer/me",Map.of("displayName"," ")).getResponse().getStatus());
        assertEquals(403,call(client,"GET","/customer/"+idB,null).getResponse().getStatus());
        assertEquals(200,call(client,"PATCH","/customer/me",Map.of("displayName","我的收藏")).getResponse().getStatus());
        assertEquals("我的收藏",jdbc.queryForObject("SELECT display_name FROM customer WHERE id=?",String.class,idA));
        assertEquals("展示會員",jdbc.queryForObject("SELECT display_name FROM customer WHERE id=?",String.class,idB));
    }
    @Test void realMysqlConcurrentCanonicalUsernameHasOneWinner() throws Exception {
        String name=username();var guest=guest();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var one=pool.submit(()->{start.await();return register(guest,name).getResponse().getStatus();});
            var two=pool.submit(()->{start.await();return register(guest,name.toUpperCase(Locale.ROOT)).getResponse().getStatus();});start.countDown();
            assertEquals(Set.of(201,409),Set.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS)));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE username=?",Integer.class,name));
        }finally{pool.shutdownNow();}
    }
    @Test void cookieAndRealmIsolationPreserveOtherAudienceLogout() throws Exception {
        String name=username();register(guest(),name);var customer=login(name);var staff=guest();
        var login=mvc.perform(path(post("/ELPSY/api/admin/login"),"/admin/login").cookie(staff.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN",staff.csrf()).param("username","shop_admin").param("password",System.getenv("DEMO_ADMIN_PASSWORD")).param("deviceId",UUID.randomUUID().toString())).andReturn();assertEquals(200,login.getResponse().getStatus());
        Cookie staffAccess=cookie(login,"accessToken"),staffRefresh=cookie(login,"refreshToken");
        var customerAsStaff=new Client(customer.csrf(),List.of(customer.cookies().get(0),new Cookie("accessToken",customer.cookies().get(1).getValue())));
        assertEquals(401,call(customerAsStaff,"GET","/auth/me",null).getResponse().getStatus());
        var staffAsCustomer=new Client(staff.csrf(),List.of(staff.cookies().get(0),new Cookie("customerAccessToken",staffAccess.getValue())));
        assertEquals(401,call(staffAsCustomer,"GET","/customer/me",null).getResponse().getStatus());
        assertEquals(401,call(customer,"GET","/admin/role",null).getResponse().getStatus());
        var mixed=new Client(customer.csrf(),List.of(customer.cookies().get(0),customer.cookies().get(1),customer.cookies().get(2),staffAccess,staffRefresh));
        assertEquals(200,call(mixed,"POST","/customer/auth/logout",null).getResponse().getStatus());assertEquals(200,call(mixed,"GET","/auth/me",null).getResponse().getStatus());
        var next=login(name);mixed=new Client(next.csrf(),List.of(next.cookies().get(0),next.cookies().get(1),next.cookies().get(2),staffAccess,staffRefresh));
        assertEquals(200,call(mixed,"POST","/admin/logout",null).getResponse().getStatus());assertEquals(200,call(mixed,"GET","/customer/me",null).getResponse().getStatus());assertEquals(401,call(mixed,"GET","/auth/me",null).getResponse().getStatus());
    }
    @Test void realRedisRefreshRaceReplayAndDeviceLogout() throws Exception {
        String name=username();register(guest(),name);var client=login(name);var otherDevice=login(name);var pool=Executors.newFixedThreadPool(4);var start=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<MvcResult>>();for(int i=0;i<4;i++)futures.add(pool.submit(()->{start.await();return call(client,"POST","/customer/auth/refresh",null);}));start.countDown();
            var winners=new ArrayList<MvcResult>();for(var f:futures){var r=f.get(15,TimeUnit.SECONDS);if(r.getResponse().getStatus()==200)winners.add(r);else assertEquals(401,r.getResponse().getStatus());}assertEquals(1,winners.size());var next=rotated(client,winners.get(0));
            assertEquals(401,call(client,"GET","/customer/me",null).getResponse().getStatus());assertEquals(401,call(client,"POST","/customer/auth/refresh",null).getResponse().getStatus());
            assertEquals(200,call(client,"POST","/customer/auth/logout",null).getResponse().getStatus());assertEquals(200,call(next,"GET","/customer/me",null).getResponse().getStatus());
            assertEquals(200,call(next,"POST","/customer/auth/logout",null).getResponse().getStatus());assertEquals(401,call(next,"GET","/customer/me",null).getResponse().getStatus());assertEquals(200,call(otherDevice,"GET","/customer/me",null).getResponse().getStatus());
        }finally{pool.shutdownNow();}
    }
    @Test void realMysqlDisabledCustomerCannotAccessRefreshOrLogin() throws Exception {
        String name=username();var result=register(guest(),name);String id=field(result,"id");var client=login(name);
        jdbc.update("UPDATE customer SET enabled=FALSE WHERE id=?",id);
        assertEquals(401,call(client,"GET","/customer/me",null).getResponse().getStatus());assertEquals(401,call(client,"POST","/customer/auth/refresh",null).getResponse().getStatus());
        assertEquals(401,call(guest(),"POST","/customer/auth/login",Map.of("username",name,"password",PASSWORD,"deviceId",UUID.randomUUID().toString(),"rememberMe",false)).getResponse().getStatus());
    }
}
