package dev.jackteng.figura;

import dev.jackteng.figura.utils.JwtUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 正式設定（沒有任何沙盒變數）：沙盒端點 404，任何偽造參數、header、cookie 或 JWT 標記都不能略過 MFA。 */
@SpringBootTest(properties = "shop.orders.expiry-delay-ms=600000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "SHOP_MYSQL_INTEGRATION", matches = "true")
class SandboxDisabledIntegrationTest extends ShopIntegrationSupport {
    @Autowired JwtUtil jwt;
    private int adminId() { return jdbc.queryForObject("SELECT id FROM user WHERE username='shop_admin'", Integer.class); }
    @AfterEach void dropMfa() {
        int id = adminId();
        jdbc.update("DELETE FROM staff_mfa_backup_code WHERE user_id=?", id); jdbc.update("DELETE FROM staff_mfa WHERE user_id=?", id); redis.delete("shop:mfa:fail:" + id);
    }
    private boolean session(MvcResult r) { return r.getResponse().getHeaders("Set-Cookie").stream().anyMatch(h -> h.startsWith("accessToken=")); }

    @Test void sandboxEndpointsAreNotFound() throws Exception {
        var g = guest();
        int users = jdbc.queryForObject("SELECT COUNT(*) FROM user", Integer.class), roles = jdbc.queryForObject("SELECT COUNT(*) FROM role", Integer.class);
        assertEquals(404, call(g, "GET", "/sandbox/config", null).getResponse().getStatus());
        assertEquals(404, call(g, "POST", "/sandbox/mfa-demo/accounts", null).getResponse().getStatus());
        assertEquals(users, jdbc.queryForObject("SELECT COUNT(*) FROM user", Integer.class), "正式設定不建立任何帳號");
        assertEquals(roles, jdbc.queryForObject("SELECT COUNT(*) FROM role", Integer.class), "也不建立展示角色");
    }

    @Test void forgedSandboxHintsNeverSkipMfa() throws Exception {
        var g = guest();
        var cookies = new java.util.ArrayList<>(g.cookies()); cookies.add(new Cookie("sandbox", "true")); cookies.add(new Cookie("mfaExempt", "true"));
        var r = mvc.perform(post("/figura/api/admin/login").contextPath("/figura").servletPath("/api/admin/login").cookie(cookies.toArray(Cookie[]::new)).header("X-XSRF-TOKEN", g.csrf())
                .header("X-Sandbox", "true").header("X-Sandbox-Mode", "true").header("X-Forwarded-For", "127.0.0.1").header("X-Forwarded-Host", "figura.jackteng.dev").header("X-Forwarded-Proto", "https")
                .param("username", "shop_admin").param("password", System.getenv("DEMO_ADMIN_PASSWORD")).param("deviceId", uuid())
                .param("mfaExempt", "true").param("sandbox", "true").param("accountKind", "SANDBOX_SHARED")).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        assertTrue(data(r).path("mfaSetupRequired").asBoolean() || data(r).path("mfaRequired").asBoolean(), "仍要求 MFA"); assertFalse(session(r), "沒有 session cookie");
        assertTrue(data(r).path("accountKind").isMissingNode());
    }

    @Test void forgedSharedSessionClaimIsRejected() throws Exception {
        // 即使有人取得簽章金鑰外的其他條件，正式設定下帶 SANDBOX_SHARED 標記的 session 也無效
        int id = adminId(); String device = UUID.randomUUID().toString(), jti = UUID.randomUUID().toString();
        int auth = jdbc.queryForObject("SELECT auth_version FROM user WHERE id=?", Integer.class, id);
        var claims = Map.<String, Object>of("id", id, "username", "shop_admin", "deviceId", device, "jti", jti, "rememberMe", false, "realm", "staff", "authVersion", auth, "mfa", "SANDBOX_SHARED");
        redis.opsForValue().set("shop:session:" + id + ":" + device, jti, java.time.Duration.ofMinutes(5));
        try {
            var g = guest();
            var c = new Client(g.csrf(), List.of(g.cookies().get(0), new Cookie("accessToken", jwt.genAccessToken(claims))), "");
            assertEquals(401, call(c, "GET", "/auth/me", null).getResponse().getStatus());
        } finally { redis.delete("shop:session:" + id + ":" + device); }
    }

    @Autowired org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder encoder;
    @Autowired dev.jackteng.figura.service.SandboxDemoService demo;
    @Test void leftoverDemoAccountsAreBlockedWhenSandboxIsOff() throws Exception {
        // 沙盒關閉後（同一 DB 重啟為正式設定），先前建立且尚未到期的臨時帳號也不能登入，不會變成一般員工
        String name = "mfa-demo-" + uuid().substring(0, 8), pw = "leftover-demo-password-2026";
        jdbc.update("INSERT INTO user(username,password,first_name,last_name,role,is_stop,created_time,modified_time) VALUES(?,?,'MFA 展示','','S','F',NOW(),NOW())", name, encoder.encode(pw));
        int uid = jdbc.queryForObject("SELECT id FROM user WHERE username=?", Integer.class, name);
        jdbc.update("INSERT INTO user_role(user_id,role_id) VALUES(?,2)", uid);
        jdbc.update("INSERT INTO sandbox_demo_account(user_id,expires_at) VALUES(?,CURRENT_TIMESTAMP(6)+INTERVAL 30 MINUTE)", uid);
        try {
            var g = guest();
            var login = mvc.perform(post("/figura/api/admin/login").contextPath("/figura").servletPath("/api/admin/login").cookie(g.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN", g.csrf())
                    .param("username", name).param("password", pw).param("deviceId", uuid())).andReturn();
            assertEquals(401, login.getResponse().getStatus(), text(login)); assertFalse(session(login));
        } finally {
            jdbc.update("UPDATE user SET is_stop='T' WHERE id=?", uid);
            jdbc.update("UPDATE sandbox_demo_account SET expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE user_id=?", uid);
        }
        assertTrue(demo.cleanupExpired() >= 1, "正式設定下清理仍會處理遺留的臨時帳號");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sandbox_demo_account WHERE user_id=?", Integer.class, uid));
    }

    @Autowired dev.jackteng.figura.config.SandboxSeeder seeder;
    @Test void seederRefusesOutsideSandbox(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        dev.jackteng.figura.config.SandboxSeedFixture.write(dir, dev.jackteng.figura.config.SandboxSeedFixture.catalog(dir, "prod"));
        int users = jdbc.queryForObject("SELECT COUNT(*) FROM user", Integer.class), products = jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product", Integer.class);
        // 正式設定：初始化入口立即拒絕（exit 3），不建立系統帳號、不寫任何資料
        assertEquals(3, seeder.execute(new org.springframework.boot.DefaultApplicationArguments("--sandbox.seed.dir=" + dir)));
        assertThrows(dev.jackteng.figura.config.SandboxSeeder.Refused.class, () -> seeder.seed(dir));
        // 沒有參數的一般啟動完全不動作
        seeder.run(new org.springframework.boot.DefaultApplicationArguments());
        assertEquals(users, jdbc.queryForObject("SELECT COUNT(*) FROM user", Integer.class));
        assertEquals(products, jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product", Integer.class));
    }

    @Test void staffAccountKindIsStaff() throws Exception {
        var admin = admin();
        assertEquals("STAFF", data(ok(admin, "GET", "/auth/me", null, 200)).path("accountKind").asText());
        var status = data(ok(admin, "GET", "/admin/ops/status", null, 200));
        assertFalse(status.path("sandboxMode").asBoolean()); assertEquals(0, status.path("mfaExemptAccounts").asInt());
    }
}
