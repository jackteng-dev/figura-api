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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** V19：手機＋信箱＋密碼註冊、重複檢查、多種登入識別、信箱驗證、忘記密碼與模擬寄信。 */
@SpringBootTest(properties = "MOCK_EMAIL_ENABLED=true")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "SHOP_MYSQL_INTEGRATION", matches = "true")
class CustomerEmailPhoneIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    private final Set<String> ids = ConcurrentHashMap.newKeySet();
    private static final String PASSWORD = "integration-test-password-2026", NEW_PASSWORD = "integration-new-password-2026";
    record Client(String csrf, List<Cookie> cookies) {}

    private MockHttpServletRequestBuilder path(MockHttpServletRequestBuilder b, String p) { return b.contextPath("/figura").servletPath("/api" + p); }
    private MvcResult call(Client c, String method, String p, Object body) throws Exception {
        var b = path(request(org.springframework.http.HttpMethod.valueOf(method), "/figura/api" + p), p);
        b.cookie(c.cookies().toArray(Cookie[]::new));
        if (!method.equals("GET")) b.header("X-XSRF-TOKEN", c.csrf());
        if (body != null) b.contentType("application/json").content(json.writeValueAsBytes(body));
        return mvc.perform(b).andReturn();
    }
    private Client guest() throws Exception {
        var r = mvc.perform(path(get("/figura/api/auth/csrf"), "/auth/csrf")).andReturn();
        return new Client(data(r).path("token").asText(), List.of(cookie(r, "XSRF-TOKEN")));
    }
    private Cookie cookie(MvcResult r, String name) {
        String h = r.getResponse().getHeaders("Set-Cookie").stream().filter(x -> x.startsWith(name + "=")).findFirst().orElseThrow();
        return new Cookie(name, h.split(";", 2)[0].substring(name.length() + 1));
    }
    private JsonNode body(MvcResult r) throws Exception { return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private JsonNode data(MvcResult r) throws Exception { return body(r).path("data"); }
    private String email() { return "Test." + UUID.randomUUID().toString().substring(0, 12) + "@Example.COM"; }
    private String phone() { return "09" + String.format("%08d", new Random().nextInt(100_000_000)); }
    private MvcResult register(String email, String phone) throws Exception {
        var r = call(guest(), "POST", "/customer/auth/register", Map.of("email", email, "phone", phone, "password", PASSWORD));
        if (r.getResponse().getStatus() == 201) ids.add(data(r).path("id").asText());
        return r;
    }
    private MvcResult loginRaw(String identifier, String password) throws Exception {
        return call(guest(), "POST", "/customer/auth/login", Map.of("username", identifier, "password", password, "deviceId", UUID.randomUUID().toString(), "rememberMe", false));
    }
    private Client login(String identifier, String password) throws Exception {
        var g = guest();
        var r = call(g, "POST", "/customer/auth/login", Map.of("username", identifier, "password", password, "deviceId", UUID.randomUUID().toString(), "rememberMe", false));
        assertEquals(200, r.getResponse().getStatus());
        return new Client(g.csrf(), List.of(g.cookies().get(0), cookie(r, "customerAccessToken"), cookie(r, "customerRefreshToken")));
    }
    private String latestToken(String customerId, String purpose) {
        String link = jdbc.queryForObject("SELECT link FROM customer_mock_email WHERE customer_id=? AND purpose=? ORDER BY created_at DESC LIMIT 1", String.class, customerId, purpose);
        return link.substring(link.indexOf("token=") + 6);
    }
    @AfterEach void cleanup() {
        for (String id : ids) {
            jdbc.update("DELETE FROM customer_mock_email WHERE customer_id=?", id);
            jdbc.update("DELETE FROM customer_email_token WHERE customer_id=?", id);
            jdbc.update("DELETE FROM customer WHERE id=?", id);
            for (String prefix : List.of("shop:customer:session:", "shop:customer:mobile:session:")) {
                var keys = redis.keys(prefix + id + ":*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
            }
        }
    }

    @Test void registerNormalizesAndRejectsDuplicateEmailOrPhone() throws Exception {
        String email = email(), phone = phone();
        var r = register(email, phone.substring(0, 4) + "-" + phone.substring(4, 7) + "-" + phone.substring(7));
        assertEquals(201, r.getResponse().getStatus());
        var d = data(r);
        assertEquals(email.toLowerCase(Locale.ROOT), d.path("email").asText());
        assertEquals(phone, d.path("phone").asText());
        assertFalse(d.path("emailVerified").asBoolean());
        assertEquals("會員" + phone.substring(6), d.path("displayName").asText());
        assertTrue(d.path("username").asText().startsWith("m_"));
        // 註冊即寄出模擬驗證信
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM customer_mock_email WHERE customer_id=? AND purpose='VERIFY'", Integer.class, d.path("id").asText()));
        // 重複：信箱大小寫不同、手機不同寫法都視為同一個
        var dupEmail = register(email.toUpperCase(Locale.ROOT), phone());
        assertEquals(409, dupEmail.getResponse().getStatus()); assertEquals("此 Email 已被註冊", body(dupEmail).path("message").asText());
        var dupPhone = register(email(), "+886" + phone.substring(1));
        assertEquals(409, dupPhone.getResponse().getStatus()); assertEquals("此手機號碼已被註冊", body(dupPhone).path("message").asText());
        // 格式與欄位組合
        assertEquals(400, register("not-an-email", phone()).getResponse().getStatus());
        assertEquals(400, register(email(), "0212345678").getResponse().getStatus());
        assertEquals(400, call(guest(), "POST", "/customer/auth/register", Map.of("email", email(), "password", PASSWORD)).getResponse().getStatus());
        assertEquals(400, call(guest(), "POST", "/customer/auth/register", Map.of("email", email(), "phone", phone(), "username", "mixed_style", "password", PASSWORD)).getResponse().getStatus());
    }

    @Test void concurrentSameEmailHasOneWinner() throws Exception {
        String email = email(); var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> { start.await(); return register(email, phone()).getResponse().getStatus(); });
            var b = pool.submit(() -> { start.await(); return register(email, phone()).getResponse().getStatus(); });
            start.countDown();
            assertEquals(Set.of(201, 409), Set.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
        } finally { pool.shutdownNow(); }
    }

    @Test void loginByEmailOrPhoneWithUniformFailure() throws Exception {
        String email = email(), phone = phone();
        register(email, phone);
        assertEquals(200, loginRaw(email.toUpperCase(Locale.ROOT), PASSWORD).getResponse().getStatus());
        assertEquals(200, loginRaw(phone, PASSWORD).getResponse().getStatus());
        assertEquals(200, loginRaw("+886 " + phone.substring(1), PASSWORD).getResponse().getStatus());
        var wrong = loginRaw(email, "wrong-password-123456");
        var unknown = loginRaw("nobody-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com", PASSWORD);
        assertEquals(401, wrong.getResponse().getStatus()); assertEquals(401, unknown.getResponse().getStatus());
        assertEquals(body(wrong).path("message").asText(), body(unknown).path("message").asText());
        assertEquals("帳號或密碼不正確", body(wrong).path("message").asText());
    }

    @Test void verifyEmailOnceAndResendIsThrottled() throws Exception {
        String email = email(), phone = phone();
        String id = data(register(email, phone)).path("id").asText();
        var client = login(email, PASSWORD);
        String first = latestToken(id, "VERIFY");
        // 重寄：新連結使舊連結失效；60 秒內再寄被擋
        assertEquals(200, call(client, "POST", "/customer/email/verification", null).getResponse().getStatus());
        assertEquals(429, call(client, "POST", "/customer/email/verification", null).getResponse().getStatus());
        assertEquals(400, call(guest(), "POST", "/customer/auth/email/verify", Map.of("token", first)).getResponse().getStatus());
        String token = latestToken(id, "VERIFY");
        var ok = call(guest(), "POST", "/customer/auth/email/verify", Map.of("token", token));
        assertEquals(200, ok.getResponse().getStatus()); assertTrue(data(ok).path("emailVerified").asBoolean());
        assertEquals(400, call(guest(), "POST", "/customer/auth/email/verify", Map.of("token", token)).getResponse().getStatus());
        assertEquals(409, call(client, "POST", "/customer/email/verification", null).getResponse().getStatus());
        assertEquals(400, call(guest(), "POST", "/customer/auth/email/verify", Map.of("token", "x".repeat(43))).getResponse().getStatus());
    }

    @Test void forgotAndResetPasswordRevokesSessions() throws Exception {
        String email = email(), phone = phone();
        String id = data(register(email, phone)).path("id").asText();
        var session = login(phone, PASSWORD);
        // 不存在的信箱也回 200，不寫入任何信
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM customer_mock_email", Long.class);
        assertEquals(200, call(guest(), "POST", "/customer/auth/password/forgot", Map.of("email", "nobody-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com")).getResponse().getStatus());
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM customer_mock_email", Long.class));
        assertEquals(200, call(guest(), "POST", "/customer/auth/password/forgot", Map.of("email", email)).getResponse().getStatus());
        String token = latestToken(id, "RESET");
        assertEquals(400, call(guest(), "POST", "/customer/auth/password/reset", Map.of("token", token, "password", "short")).getResponse().getStatus());
        assertEquals(200, call(guest(), "POST", "/customer/auth/password/reset", Map.of("token", token, "password", NEW_PASSWORD)).getResponse().getStatus());
        assertEquals(400, call(guest(), "POST", "/customer/auth/password/reset", Map.of("token", token, "password", NEW_PASSWORD)).getResponse().getStatus());
        assertEquals(401, call(session, "GET", "/customer/me", null).getResponse().getStatus());
        assertEquals(401, loginRaw(email, PASSWORD).getResponse().getStatus());
        assertEquals(200, loginRaw(email, NEW_PASSWORD).getResponse().getStatus());
    }

    @Test void forgotIsRateLimitedPerEmail() throws Exception {
        String email = email(); String id = data(register(email, phone())).path("id").asText();
        for (int i = 0; i < 5; i++) assertEquals(200, call(guest(), "POST", "/customer/auth/password/forgot", Map.of("email", email)).getResponse().getStatus());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM customer_mock_email WHERE customer_id=? AND purpose='RESET'", Integer.class, id));
        redis.delete("shop:customer:email:forgot:" + email.toLowerCase(Locale.ROOT));
    }

    @Test void mockEmailsVisibleToStaffOnly() throws Exception {
        String email = email(); register(email, phone());
        var customer = login(email, PASSWORD);
        assertEquals(401, call(customer, "GET", "/admin/notifications/mock-emails", null).getResponse().getStatus());
        var staff = guest();
        var login = mvc.perform(path(post("/figura/api/admin/login"), "/admin/login").cookie(staff.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN", staff.csrf())
                .param("username", "shop_admin").param("password", System.getenv("DEMO_ADMIN_PASSWORD")).param("deviceId", UUID.randomUUID().toString())).andReturn();
        assertEquals(200, login.getResponse().getStatus());
        var admin = new Client(staff.csrf(), List.of(staff.cookies().get(0), cookie(login, "accessToken"), cookie(login, "refreshToken")));
        var list = call(admin, "GET", "/admin/notifications/mock-emails", null);
        assertEquals(200, list.getResponse().getStatus());
        boolean found = false;
        for (var item : data(list).path("items")) if (item.path("recipient").asText().equals(email.toLowerCase(Locale.ROOT))) { found = true; assertTrue(item.path("link").asText().startsWith("/shop/verify-email?token=")); }
        assertTrue(found);
    }
}
