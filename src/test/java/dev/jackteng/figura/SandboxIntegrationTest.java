package dev.jackteng.figura;

import dev.jackteng.figura.service.SandboxDemoService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 公開沙盒模式（SHOP_SANDBOX_MODE=true）：共用展示帳號免 MFA 但不能改安全設定；MFA 展示臨時帳號完整走正式 MFA 流程、
 * 權限受限、有上限、到期拒絕並清理乾淨。正式模式行為見 SandboxDisabledIntegrationTest。
 */
@SpringBootTest(properties = {"shop.orders.expiry-delay-ms=600000", "SHOP_SANDBOX_MODE=true", "SANDBOX_MFA_EXEMPT_USERNAMES=" + SandboxIntegrationTest.SHARED,
        "SANDBOX_MFA_DEMO_ENABLED=true", "SANDBOX_MFA_DEMO_MAX_ACTIVE=3", "SANDBOX_MFA_DEMO_TTL_MINUTES=30", "shop.sandbox.cleanup-delay-ms=3600000",
        // 與 SandboxSeederIntegrationTest 完全相同，共用同一個 Spring context（CI MySQL 連線數有限）
        "MOCK_PAYMENTS_ENABLED=true", "MOCK_FULFILLMENT_ENABLED=true", "MOCK_RETURNS_ENABLED=true", "MOCK_REFUNDS_ENABLED=true"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "SHOP_MYSQL_INTEGRATION", matches = "true")
class SandboxIntegrationTest extends ShopIntegrationSupport {
    static final String SHARED = "sandbox_shared_it";
    private static final String SHARED_PASSWORD = "sandbox-shared-test-password-2026";
    @Autowired BCryptPasswordEncoder encoder;
    @Autowired SandboxDemoService demo;

    private int id(String username) { return jdbc.queryForObject("SELECT id FROM user WHERE username=?", Integer.class, username); }
    private void purgeDemo() {
        jdbc.update("UPDATE sandbox_demo_account SET expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND");
        demo.cleanupExpired();
        redis.delete("shop:sandbox:mfa-demo:created:" + (System.currentTimeMillis() / 3_600_000L));
    }
    @BeforeEach void setUp() {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE username=?", Integer.class, SHARED) == 0) {
            jdbc.update("INSERT INTO user(username,password,first_name,last_name,role,is_stop,created_time,modified_time) VALUES(?,?,?,'','S','F',NOW(),NOW())", SHARED, encoder.encode(SHARED_PASSWORD), SHARED);
            jdbc.update("INSERT INTO user_role(user_id,role_id) VALUES(?,1)", id(SHARED));
        }
        jdbc.update("UPDATE user SET is_stop='F',password=? WHERE username=?", encoder.encode(SHARED_PASSWORD), SHARED);
        purgeDemo();
    }
    @AfterEach void tearDown() {
        purgeDemo();
        jdbc.update("UPDATE user SET is_stop='T' WHERE username=?", SHARED);   // 測試以外不留下可登入的完整管理員
        int admin = id("shop_admin");
        jdbc.update("DELETE FROM staff_mfa_backup_code WHERE user_id=?", admin); jdbc.update("DELETE FROM staff_mfa WHERE user_id=?", admin); redis.delete("shop:mfa:fail:" + admin);
    }

    private MvcResult password(Client c, String username, String password) throws Exception {
        return mvc.perform(post("/figura/api/admin/login").contextPath("/figura").servletPath("/api/admin/login").cookie(c.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN", c.csrf())
                .param("username", username).param("password", password).param("deviceId", uuid())).andReturn();
    }
    private boolean session(MvcResult r) { return r.getResponse().getHeaders("Set-Cookie").stream().anyMatch(h -> h.startsWith("accessToken=")); }
    private Client signedIn(Client g, MvcResult r) { return new Client(g.csrf(), List.of(g.cookies().get(0), cookie(r, "accessToken"), cookie(r, "refreshToken")), ""); }
    private long step() { return System.currentTimeMillis() / 1000 / 30; }

    @Test void configExposesOnlyFlags() throws Exception {
        var body = data(ok(guest(), "GET", "/sandbox/config", null, 200));
        assertTrue(body.path("quickDemo").asBoolean()); assertTrue(body.path("mfaDemo").asBoolean()); assertEquals(30, body.path("mfaDemoTtlMinutes").asInt());
        assertFalse(text(call(guest(), "GET", "/sandbox/config", null)).contains(SHARED), "不公開共用帳號名稱");
    }

    @Test void sharedAccountSignsInWithoutMfaButCannotChangeSecurity() throws Exception {
        var g = guest();
        var r = password(g, SHARED, SHARED_PASSWORD);
        assertEquals(200, r.getResponse().getStatus(), text(r)); assertTrue(session(r), "共用展示帳號直接取得 session");
        var body = data(r);
        assertFalse(body.path("mfaSetupRequired").asBoolean()); assertFalse(body.path("mfaRequired").asBoolean());
        assertTrue(body.path("backupCodes").isMissingNode(), "不產生備援碼");
        assertEquals("SANDBOX_SHARED", body.path("accountKind").asText());
        var s = signedIn(g, r);
        var me = data(ok(s, "GET", "/auth/me", null, 200));
        assertEquals("SANDBOX_SHARED", me.path("accountKind").asText()); assertFalse(me.path("mfaBackupAckPending").asBoolean());
        assertEquals(200, call(s, "GET", "/admin/reports/low-stock", null).getResponse().getStatus(), "權限照原 RBAC");

        int sharedId = id(SHARED), events = jdbc.queryForObject("SELECT COUNT(*) FROM staff_access_event", Integer.class);
        for (var forbidden : List.of(
                new Object[]{"POST", "/admin/mfa/backup-codes", Map.of("code", "123456")},
                new Object[]{"POST", "/admin/mfa/backup-codes/ack", Map.of("batchId", uuid(), "downloaded", true, "confirmed", true)},
                new Object[]{"POST", "/admin/access/staff", Map.of("requestId", uuid())},
                new Object[]{"PUT", "/admin/access/staff/" + sharedId, Map.of("requestId", uuid())},
                new Object[]{"POST", "/admin/access/staff/" + id("shop_admin") + "/mfa/reset", Map.of("requestId", uuid(), "note", "x")},
                new Object[]{"POST", "/admin/access/roles", Map.of("requestId", uuid())})) {
            var res = call(s, (String) forbidden[0], (String) forbidden[1], forbidden[2]);
            assertEquals(403, res.getResponse().getStatus(), forbidden[1] + " → " + text(res));
            assertTrue(text(res).contains("SANDBOX_SHARED_ACCOUNT"), text(res));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM staff_mfa WHERE user_id=?", Integer.class, sharedId), "沒有 MFA 寫入");
        assertEquals(events, jdbc.queryForObject("SELECT COUNT(*) FROM staff_access_event", Integer.class), "沒有存取治理寫入");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE id=? AND password IS NOT NULL", Integer.class, sharedId));
        assertTrue(encoder.matches(SHARED_PASSWORD, jdbc.queryForObject("SELECT password FROM user WHERE id=?", String.class, sharedId)), "密碼未被修改");
        // 刷新 session 後仍維持共用帳號標記
        var refreshed = ok(s, "POST", "/auth/refresh", null, 200);
        var s2 = new Client(s.csrf(), List.of(s.cookies().get(0), cookie(refreshed, "accessToken"), cookie(refreshed, "refreshToken")), "");
        assertEquals("SANDBOX_SHARED", data(ok(s2, "GET", "/auth/me", null, 200)).path("accountKind").asText());
    }

    @Test void variantsDisabledAccountsAndOtherStaffAreNotExempt() throws Exception {
        for (String variant : List.of("SANDBOX_SHARED_IT", "Sandbox_shared_it", " " + SHARED, SHARED + " ")) {
            var r = password(guest(), variant, SHARED_PASSWORD);
            assertFalse(session(r), "變形帳號不豁免：" + variant);
            assertFalse(data(r).path("mfaSetupRequired").asBoolean(), "也不能替共用帳號開始綁定：" + variant);
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM staff_mfa WHERE user_id=?", Integer.class, id(SHARED)));
        jdbc.update("UPDATE user SET is_stop='T' WHERE username=?", SHARED);
        var stopped = password(guest(), SHARED, SHARED_PASSWORD);
        assertEquals(401, stopped.getResponse().getStatus()); assertFalse(session(stopped), "停用帳號拒絕");
        jdbc.update("UPDATE user SET is_stop='F' WHERE username=?", SHARED);
        assertEquals(401, password(guest(), SHARED, "wrong-password-0000").getResponse().getStatus());
        var admin = password(guest(), "shop_admin", System.getenv("DEMO_ADMIN_PASSWORD"));
        assertEquals(200, admin.getResponse().getStatus());
        // 已綁定（其他測試留下）回 mfaRequired、未綁定回 mfaSetupRequired；兩者都必須再過 MFA，且都不發 session
        assertTrue(data(admin).path("mfaSetupRequired").asBoolean() || data(admin).path("mfaRequired").asBoolean(), "非豁免員工仍強制 MFA"); assertFalse(session(admin));
    }

    private com.fasterxml.jackson.databind.JsonNode createDemo() throws Exception {
        var r = ok(guest(), "POST", "/sandbox/mfa-demo/accounts", null, 200);
        return data(r);
    }

    @Test void mfaDemoAccountWalksTheRealMfaFlow() throws Exception {
        var created = createDemo();
        String username = created.path("username").asText(), pw = created.path("password").asText();
        assertTrue(username.matches("mfa-demo-[a-z0-9]{8}"), username); assertTrue(pw.length() >= 20); assertFalse(created.path("expiresAt").isMissingNode());
        assertNotEquals(pw, jdbc.queryForObject("SELECT password FROM user WHERE username=?", String.class, username), "只存 bcrypt");
        // 首次登入 → 強制綁定（正式程式路徑）
        var g = guest();
        var first = password(g, username, pw);
        assertTrue(data(first).path("mfaSetupRequired").asBoolean()); assertFalse(session(first));
        String token = data(first).path("mfaSetupToken").asText();
        String secret = data(ok(g, "POST", "/admin/login/mfa/setup", Map.of("setupToken", token), 200)).path("secret").asText();
        long enrolledStep = step();
        var enrolled = ok(g, "POST", "/admin/login/mfa/enroll", Map.of("setupToken", token, "code", StaffLogin.totp(secret, enrolledStep)), 200);
        var eb = data(enrolled);
        assertTrue(eb.path("backupCodesRequired").asBoolean(), "展示帳號比照管理員強制保存備援碼");
        assertEquals("SANDBOX_MFA_DEMO", eb.path("accountKind").asText());
        var codes = new ArrayList<String>(); eb.path("backupCodes").forEach(c -> codes.add(c.asText())); assertEquals(10, codes.size());
        var s = signedIn(g, enrolled);
        assertTrue(text(call(s, "GET", "/admin/reports/low-stock", null)).contains("MFA_BACKUP_ACK_REQUIRED"));
        assertEquals(400, call(s, "POST", "/admin/mfa/backup-codes/ack", Map.of("batchId", eb.path("backupBatchId").asText(), "downloaded", false, "confirmed", false)).getResponse().getStatus());
        ok(s, "POST", "/admin/mfa/backup-codes/ack", Map.of("batchId", eb.path("backupBatchId").asText(), "downloaded", true, "confirmed", true), 200);
        var me = data(ok(s, "GET", "/auth/me", null, 200));
        assertEquals("SANDBOX_MFA_DEMO", me.path("accountKind").asText()); assertFalse(me.path("sandboxExpiresAt").isMissingNode());
        assertEquals(200, call(s, "GET", "/admin/reports/low-stock", null).getResponse().getStatus(), "唯讀儀表板");
        // 不能存取治理／商品／訂單／他人 MFA
        int shared = id(SHARED);
        for (var forbidden : List.of(new String[]{"GET", "/admin/access/staff"}, new String[]{"GET", "/admin/access/roles"}, new String[]{"GET", "/admin/products"},
                new String[]{"GET", "/admin/orders"}, new String[]{"GET", "/admin/ops/status"}, new String[]{"POST", "/admin/access/staff"},
                new String[]{"POST", "/admin/access/staff/" + shared + "/mfa/reset"}, new String[]{"POST", "/admin/products"}, new String[]{"PUT", "/admin/access/staff/" + shared}))
            assertEquals(403, call(s, forbidden[0], forbidden[1], Map.of("requestId", uuid(), "note", "x")).getResponse().getStatus(), forbidden[0] + " " + forbidden[1]);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM staff_mfa WHERE user_id=?", Integer.class, shared));
        ok(s, "POST", "/admin/logout", null, 200);

        // 登出後重新登入 → 要求 TOTP
        var g2 = guest();
        var second = password(g2, username, pw);
        assertTrue(data(second).path("mfaRequired").asBoolean()); assertFalse(session(second));
        var totpLogin = ok(g2, "POST", "/admin/login/mfa", Map.of("challenge", data(second).path("mfaChallenge").asText(), "code", StaffLogin.totp(secret, enrolledStep + 1)), 200);
        assertTrue(session(totpLogin));
        // 一次性備援碼
        var g3 = guest();
        var third = password(g3, username, pw);
        assertTrue(session(ok(g3, "POST", "/admin/login/mfa", Map.of("challenge", data(third).path("mfaChallenge").asText(), "code", codes.get(0)), 200)));
        var g4 = guest();
        var fourth = password(g4, username, pw);
        assertEquals(401, call(g4, "POST", "/admin/login/mfa", Map.of("challenge", data(fourth).path("mfaChallenge").asText(), "code", codes.get(0))).getResponse().getStatus(), "同一備援碼不能再用");
    }

    @Test void limitsExpiryAndCleanup() throws Exception {
        var a = createDemo(); createDemo(); createDemo();
        assertEquals(429, call(guest(), "POST", "/sandbox/mfa-demo/accounts", null).getResponse().getStatus(), "超過同時上限");
        purgeDemo();
        redis.opsForValue().set("shop:sandbox:mfa-demo:created:" + (System.currentTimeMillis() / 3_600_000L), "200");
        assertEquals(429, call(guest(), "POST", "/sandbox/mfa-demo/accounts", null).getResponse().getStatus(), "超過每小時上限");
        redis.delete("shop:sandbox:mfa-demo:created:" + (System.currentTimeMillis() / 3_600_000L));

        // 到期：既有 session 與新登入都被拒絕；清理後 DB 與 Redis 無殘留
        var created = createDemo();
        String username = created.path("username").asText(), pw = created.path("password").asText();
        var g = guest();
        String token = data(password(g, username, pw)).path("mfaSetupToken").asText();
        String secret = data(ok(g, "POST", "/admin/login/mfa/setup", Map.of("setupToken", token), 200)).path("secret").asText();
        var enrolled = ok(g, "POST", "/admin/login/mfa/enroll", Map.of("setupToken", token, "code", StaffLogin.totp(secret, step())), 200);
        var s = signedIn(g, enrolled);
        int uid = id(username);
        assertFalse(redis.keys("shop:session:" + uid + ":*").isEmpty());
        jdbc.update("UPDATE sandbox_demo_account SET expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE user_id=?", uid);
        assertEquals(401, call(s, "GET", "/auth/me", null).getResponse().getStatus(), "到期後 session 失效");
        var expired = password(guest(), username, pw);
        assertEquals(401, expired.getResponse().getStatus()); assertFalse(session(expired));
        assertTrue(demo.cleanupExpired() >= 1);
        for (String table : List.of("staff_mfa", "staff_mfa_backup_code", "staff_mfa_event", "user_role", "sandbox_demo_account"))
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Integer.class, uid), table);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE id=?", Integer.class, uid));
        assertTrue(redis.keys("shop:session:" + uid + ":*").isEmpty(), "Redis session 已清除");
        assertNotNull(a);
    }

    @Test void referencedDemoAccountIsTombstonedInsteadOfBlockingCleanup() throws Exception {
        var created = createDemo();
        String username = created.path("username").asText(), pw = created.path("password").asText();
        int uid = id(username);
        // 其他資料以外鍵參照臨時帳號（模擬客服指派等業務參照）：清理不能卡住，也不能刪掉參照它的歷史
        String ref = uuid();
        jdbc.update("INSERT INTO staff_mfa_event(id,user_id,action,actor_id,note) VALUES(?,?,'RESET',?,?)", ref, id(SHARED), uid, "測試：外鍵參照臨時帳號");
        jdbc.update("UPDATE sandbox_demo_account SET expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE user_id=?", uid);
        assertEquals(1, demo.cleanupExpired());
        assertEquals("T", jdbc.queryForObject("SELECT is_stop FROM user WHERE id=?", String.class, uid), "保留為停用墓碑");
        assertFalse(encoder.matches(pw, jdbc.queryForObject("SELECT password FROM user WHERE id=?", String.class, uid)), "原密碼失效");
        for (String table : List.of("staff_mfa", "staff_mfa_backup_code", "user_role", "sandbox_demo_account"))
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Integer.class, uid), table);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM staff_mfa_event WHERE id=?", Integer.class, ref), "參照它的歷史保留");
        assertEquals(401, password(guest(), username, pw).getResponse().getStatus());
        assertEquals(0, demo.cleanupExpired(), "下次排程不再卡在同一筆");
    }

    @Test void demoRoleHasOnlyReadOnlyPermissions() throws Exception {
        createDemo();
        var codes = jdbc.queryForList("SELECT p.code FROM role r JOIN role_permission rp ON rp.role_id=r.id JOIN permissions_backend p ON p.id=rp.permission_id WHERE r.name=? ORDER BY p.code", String.class, SandboxDemoService.ROLE_NAME);
        assertEquals(List.of("reports.low-stock", "reports.sales", "workspace.read"), codes);
    }
}
