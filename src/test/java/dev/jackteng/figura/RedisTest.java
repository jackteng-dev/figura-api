package dev.jackteng.figura;

import dev.jackteng.figura.pojo.Permission;
import dev.jackteng.figura.pojo.User;
import dev.jackteng.figura.service.*;
import dev.jackteng.figura.utils.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="SHOP_REDIS_INTEGRATION", matches="true")
class RedisTest {
    @Test void refreshRaceHasOneWinnerAndReplayCannotRevokeWinner() throws Exception {
        withRedis((sessions,jwt) -> {
            String device = UUID.randomUUID().toString(); var login = new MockHttpServletResponse(); sessions.completeMfa("c|"+device,"123456",login);
            String oldRefresh = cookie(login,"refreshToken"), oldAccess = cookie(login,"accessToken");
            var pool = Executors.newFixedThreadPool(4); var start = new CountDownLatch(1);
            try {
                var results = new ArrayList<Future<MockHttpServletResponse>>();
                for(int i=0;i<4;i++) results.add(pool.submit(() -> { start.await(); var response = new MockHttpServletResponse(); try { sessions.refresh(oldRefresh,response); return response; } catch(ResponseStatusException e) { return null; } }));
                start.countDown(); var winners = new ArrayList<MockHttpServletResponse>();
                for(var future:results) { var response=future.get(10,TimeUnit.SECONDS); if(response!=null) winners.add(response); }
                assertEquals(1,winners.size()); assertThrows(ResponseStatusException.class, () -> sessions.authenticate(oldAccess));
                String newAccess=cookie(winners.get(0),"accessToken"); assertEquals("test",sessions.authenticate(newAccess).getUsername());
                sessions.logout(oldRefresh,new MockHttpServletResponse()); assertNotNull(sessions.authenticate(newAccess));
                sessions.logout(cookie(winners.get(0),"refreshToken"),new MockHttpServletResponse()); assertThrows(ResponseStatusException.class, () -> sessions.authenticate(newAccess));
            } finally { pool.shutdownNow(); }
        });
    }
    @Test void logoutIsPerDevice() throws Exception {
        withRedis((sessions,jwt) -> {
            var one = new MockHttpServletResponse(); var two = new MockHttpServletResponse();
            sessions.completeMfa("c|"+UUID.randomUUID(),"123456",one); sessions.completeMfa("c|"+UUID.randomUUID(),"123456",two);
            sessions.logout(cookie(one,"refreshToken"),new MockHttpServletResponse());
            assertThrows(ResponseStatusException.class, () -> sessions.authenticate(cookie(one,"accessToken")));
            assertNotNull(sessions.authenticate(cookie(two,"accessToken")));
        });
    }
    private interface Check { void run(AdminSessionService sessions, JwtUtil jwt) throws Exception; }
    private void withRedis(Check check) throws Exception {
        int port=Integer.parseInt(System.getenv("SHOP_REDIS_TEST_PORT"));
        var config=new RedisStandaloneConfiguration("127.0.0.1",port); config.setDatabase(15);
        var factory=new LettuceConnectionFactory(config); factory.afterPropertiesSet(); factory.start();
        var redis=new StringRedisTemplate(factory); int id=100000+new java.security.SecureRandom().nextInt(1000000);
        try {
            var users=mock(AdminUserService.class); var roles=mock(RoleService.class); var passwords=new BCryptPasswordEncoder();
            var user=new User(); user.setId(id); user.setUsername("test"); user.setIsStop("F"); user.setPassword(passwords.encode("test-password"));
            when(users.findByUserName("test")).thenReturn(user); var permission=new Permission(); permission.setName("工作首頁"); when(roles.getUserBackendPermission(id)).thenReturn(List.of(permission));
            // 強制 MFA：密碼登入不發 session；這裡模擬「已通過第二因素」，經正式的 completeMfa 發 session，測試 Redis session 輪換／登出
            var mfa=mock(StaffMfaService.class); when(mfa.enabled(id)).thenReturn(true);
            when(mfa.verify(anyString(),eq("123456"))).thenAnswer(a->new StaffMfaService.Pending(id,"test",((String)a.getArgument(0)).substring(2),false,0));
            var jwt=new JwtUtil("redis-integration-test-only-0123456789",false); check.run(new AdminSessionService(users,roles,passwords,redis,jwt,mfa),jwt);
        } finally { var keys=redis.keys("shop:session:"+id+":*"); if(keys!=null&&!keys.isEmpty()) redis.delete(keys); factory.destroy(); }
    }
    private String cookie(MockHttpServletResponse response,String name) {
        return response.getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(name+"=")).findFirst().orElseThrow().split(";",2)[0].substring(name.length()+1);
    }
}
