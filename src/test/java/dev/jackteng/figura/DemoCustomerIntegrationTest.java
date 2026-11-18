package dev.jackteng.figura;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.figura.config.DemoCustomerSeeder;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 沙盒示範會員：缺少就建立、已存在只核對（含密碼）、不重設、公開示範帳號不能被重設密碼、資料被清空後可補建。 */
@SpringBootTest(properties = {"MOCK_EMAIL_ENABLED=true", "DEMO_CUSTOMER_PASSWORD=" + DemoCustomerIntegrationTest.PASSWORD,
        "DEMO_CUSTOMER_SELF_HEAL=true", "shop.demo.customer-check-delay-ms=3600000"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "SHOP_MYSQL_INTEGRATION", matches = "true")
class DemoCustomerIntegrationTest {
    static final String PASSWORD = "integration-demo-customer-2026";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired DemoCustomerSeeder seeder;

    private MockHttpServletRequestBuilder path(MockHttpServletRequestBuilder b, String p) { return b.contextPath("/figura").servletPath("/api" + p); }
    private record Guest(String csrf, Cookie cookie) {}
    private Guest guest() throws Exception {
        var r = mvc.perform(path(get("/figura/api/auth/csrf"), "/auth/csrf")).andReturn();
        String h = r.getResponse().getHeaders("Set-Cookie").stream().filter(x -> x.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        return new Guest(json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data").path("token").asText(),
                new Cookie("XSRF-TOKEN", h.split(";", 2)[0].substring("XSRF-TOKEN=".length())));
    }
    private MvcResult post(String p, Object body) throws Exception {
        var g = guest();
        return mvc.perform(path(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/figura/api" + p), p)
                .cookie(g.cookie()).header("X-XSRF-TOKEN", g.csrf()).contentType("application/json").content(json.writeValueAsBytes(body))).andReturn();
    }
    private int login(String identifier, String password) throws Exception {
        return post("/customer/auth/login", Map.of("username", identifier, "password", password, "deviceId", UUID.randomUUID().toString(), "rememberMe", false)).getResponse().getStatus();
    }
    private String demoId() { return jdbc.queryForList("SELECT id FROM customer WHERE email=?", String.class, DemoCustomerSeeder.EMAIL).stream().findFirst().orElse(null); }
    private void wipeDemo() {
        String id = demoId();
        if (id == null) return;
        jdbc.update("DELETE FROM customer_mock_email WHERE customer_id=?", id);
        jdbc.update("DELETE FROM customer_email_token WHERE customer_id=?", id);
        jdbc.update("DELETE FROM customer WHERE id=?", id);
        for (String prefix : List.of("shop:customer:session:", "shop:customer:mobile:session:")) {
            var keys = redis.keys(prefix + id + ":*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        }
    }
    @BeforeEach void clean() { wipeDemo(); }
    @AfterEach void cleanup() { wipeDemo(); }

    @Test void createsOnceThenOnlyChecksAndLoginWorksByEmailOrPhone() throws Exception {
        assertEquals(DemoCustomerSeeder.Status.CREATED, seeder.ensure());
        String id = demoId(), hash = jdbc.queryForObject("SELECT password_hash FROM customer WHERE id=?", String.class, id);
        assertEquals(DemoCustomerSeeder.Status.OK, seeder.ensure());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE email=? OR phone=?", Integer.class, DemoCustomerSeeder.EMAIL, DemoCustomerSeeder.PHONE));
        assertEquals(hash, jdbc.queryForObject("SELECT password_hash FROM customer WHERE id=?", String.class, id));
        assertEquals(200, login(DemoCustomerSeeder.EMAIL, PASSWORD));
        assertEquals(200, login("0900-000-000", PASSWORD));
    }

    @Test void passwordOrDataMismatchIsReportedButNeverOverwritten() throws Exception {
        seeder.ensure();
        String id = demoId(), other = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("someone-changed-it-2026");
        jdbc.update("UPDATE customer SET password_hash=? WHERE id=?", other, id);
        assertEquals(DemoCustomerSeeder.Status.PASSWORD_MISMATCH, seeder.ensure());
        assertEquals(other, jdbc.queryForObject("SELECT password_hash FROM customer WHERE id=?", String.class, id));
        jdbc.update("UPDATE customer SET phone='0900000009' WHERE id=?", id);
        assertEquals(DemoCustomerSeeder.Status.MISMATCH, seeder.ensure());
        assertThrows(IllegalStateException.class, seeder::ensureAtStartup);
        assertEquals("0900000009", jdbc.queryForObject("SELECT phone FROM customer WHERE id=?", String.class, id));
    }

    @Test void publicDemoAccountCannotBeResetByVisitors() throws Exception {
        seeder.ensure();
        String id = demoId();
        assertEquals(200, post("/customer/auth/password/forgot", Map.of("email", DemoCustomerSeeder.EMAIL)).getResponse().getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM customer_email_token WHERE customer_id=? AND purpose='RESET'", Integer.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM customer_mock_email WHERE customer_id=? AND purpose='RESET'", Integer.class, id));
        assertEquals(200, login(DemoCustomerSeeder.EMAIL, PASSWORD));
    }

    @Test void selfHealRecreatesAfterAResetWipedIt() throws Exception {
        seeder.ensure();
        wipeDemo(); // 模擬每日重置清空資料而 API 沒有重啟
        assertNull(demoId());
        seeder.heal();
        assertNotNull(demoId());
        assertEquals(200, login(DemoCustomerSeeder.EMAIL, PASSWORD));
    }
}
