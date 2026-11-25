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

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 員工後台強制雙因素（綁定流程、每個 session 驗證、備援碼、重設、舊 session 失效、節流）與營運報表。 */
// MFA 測試金鑰在 src/test/resources/application.properties；屬性與其他測試相同以共用 context
@SpringBootTest(properties = "shop.orders.expiry-delay-ms=600000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "SHOP_MYSQL_INTEGRATION", matches = "true")
class OpsIntegrationTest extends ShopIntegrationSupport {
    @Autowired JwtUtil jwt;
    private int id(String username) { return jdbc.queryForObject("SELECT id FROM user WHERE username=?", Integer.class, username); }
    private void clearMfa(String username) {
        int id = id(username);
        jdbc.update("DELETE FROM staff_mfa_backup_code WHERE user_id=?", id); jdbc.update("DELETE FROM staff_mfa WHERE user_id=?", id); redis.delete("shop:mfa:fail:" + id);
    }
    @AfterEach void dropMfa() { clearMfa("shop_admin"); if (System.getenv("DEMO_STAFF_PASSWORD") != null) clearMfa("shop_staff"); }

    private MvcResult password(Client c, String username, String password) throws Exception {
        return mvc.perform(post("/figura/api/admin/login").contextPath("/figura").servletPath("/api/admin/login").cookie(c.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN", c.csrf())
                .param("username", username).param("password", password).param("deviceId", uuid())).andReturn();
    }
    private boolean session(MvcResult r) { return r.getResponse().getHeaders("Set-Cookie").stream().anyMatch(h -> h.startsWith("accessToken=")); }
    private long step() { return System.currentTimeMillis() / 1000 / 30; }

    @Test void firstLoginForcesEnrollmentAndEverySessionNeedsTotp() throws Exception {
        clearMfa("shop_admin");
        var g = guest(); String pw = System.getenv("DEMO_ADMIN_PASSWORD");
        var r = password(g, "shop_admin", pw);
        assertEquals(200, r.getResponse().getStatus());
        var body = data(r);
        assertTrue(body.path("mfaSetupRequired").asBoolean(), "未綁定先進入綁定流程"); assertFalse(session(r), "綁定前不發 session");
        for (String f : List.of("id", "username", "permissions", "email")) assertTrue(body.path(f).isMissingNode() || body.path(f).isNull(), "綁定前不回傳員工資料：" + f);
        assertEquals(401, call(g, "GET", "/admin/reports/low-stock", null).getResponse().getStatus(), "沒有 session 不能存取業務 API");
        String token = body.path("mfaSetupToken").asText();
        var setup = data(ok(g, "POST", "/admin/login/mfa/setup", Map.of("setupToken", token), 200));
        String secret = setup.path("secret").asText();
        assertTrue(setup.path("otpauthUri").asText().startsWith("otpauth://totp/Figura"));
        assertNotEquals(secret, jdbc.queryForObject("SELECT secret_enc FROM staff_mfa WHERE user_id=?", String.class, id("shop_admin")), "密鑰加密保存");
        String wrong = "000000".equals(StaffLogin.totp(secret, step())) ? "111111" : "000000";
        assertEquals(401, call(g, "POST", "/admin/login/mfa/enroll", Map.of("setupToken", token, "code", wrong)).getResponse().getStatus());
        var enrolled = call(g, "POST", "/admin/login/mfa/enroll", Map.of("setupToken", token, "code", StaffLogin.totp(secret, step())));
        assertEquals(200, enrolled.getResponse().getStatus(), text(enrolled)); assertTrue(session(enrolled));
        var codes = new ArrayList<String>(); data(enrolled).path("backupCodes").forEach(c -> codes.add(c.asText()));
        assertEquals(10, codes.size()); assertTrue(codes.get(0).matches("[A-Z2-9]{5}-[A-Z2-9]{5}"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM staff_mfa_backup_code WHERE code_hash=?", Integer.class, codes.get(0).replace("-", "")), "備援碼只存雜湊");
        // 系統管理員：必須下載並確認保存，才能使用業務 API
        var eb = data(enrolled);
        assertTrue(eb.path("backupCodesRequired").asBoolean()); assertTrue(eb.path("mfaBackupAckPending").asBoolean());
        assertEquals("shop_admin", eb.path("username").asText()); assertFalse(eb.path("backupCodesGeneratedAt").isMissingNode());
        var fresh = new Client(g.csrf(), List.of(g.cookies().get(0), cookie(enrolled, "accessToken"), cookie(enrolled, "refreshToken")), String.valueOf(id("shop_admin")));
        var blocked = call(fresh, "GET", "/admin/reports/low-stock", null);
        assertEquals(403, blocked.getResponse().getStatus()); assertTrue(text(blocked).contains("MFA_BACKUP_ACK_REQUIRED"));
        assertTrue(data(ok(fresh, "GET", "/auth/me", null, 200)).path("mfaBackupAckPending").asBoolean(), "前端據此導向保存確認");
        assertEquals(400, call(fresh, "POST", "/admin/mfa/backup-codes/ack", Map.of("downloaded", false, "confirmed", false)).getResponse().getStatus(), "管理員不能略過");
        assertEquals(400, call(fresh, "POST", "/admin/mfa/backup-codes/ack", Map.of("downloaded", true, "confirmed", false)).getResponse().getStatus(), "需勾選保存確認");
        assertFalse(data(ok(fresh, "POST", "/admin/mfa/backup-codes/ack", Map.of("downloaded", true, "confirmed", true), 200)).path("backupAckPending").asBoolean());
        assertEquals(200, call(fresh, "GET", "/admin/reports/low-stock", null).getResponse().getStatus(), "確認後可使用業務 API");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM staff_mfa_event WHERE user_id=? AND action='BACKUP_ACK' AND created_at>=NOW()-INTERVAL 1 MINUTE", Integer.class, id("shop_admin")) >= 1 ? 1 : 0);
        assertEquals(401, call(g, "POST", "/admin/login/mfa/enroll", Map.of("setupToken", token, "code", StaffLogin.totp(secret, step() + 1))).getResponse().getStatus(), "綁定 token 只能用一次");

        // 已綁定：每個新 session 都要 TOTP；密碼正確只拿到 challenge
        var r2 = password(guest(), "shop_admin", pw);
        assertTrue(data(r2).path("mfaRequired").asBoolean()); assertFalse(data(r2).path("mfaSetupRequired").asBoolean()); assertFalse(session(r2));
        String c2 = data(r2).path("mfaChallenge").asText();
        assertEquals(401, call(g, "POST", "/admin/login/mfa/setup", Map.of("setupToken", c2)).getResponse().getStatus(), "challenge 不能拿去重新綁定");
        assertEquals(401, call(g, "POST", "/admin/login/mfa", Map.of("challenge", c2, "code", StaffLogin.totp(secret, step()))).getResponse().getStatus(), "同一時間窗的驗證碼不能重用");
        var ok2 = call(g, "POST", "/admin/login/mfa", Map.of("challenge", c2, "code", StaffLogin.totp(secret, step() + 1)));
        assertEquals(200, ok2.getResponse().getStatus(), text(ok2)); assertTrue(session(ok2));
        assertEquals(401, call(g, "POST", "/admin/login/mfa", Map.of("challenge", c2, "code", codes.get(0))).getResponse().getStatus(), "challenge 只能用一次");

        // 備援碼：可代替 TOTP，一次性
        String c3 = data(password(guest(), "shop_admin", pw)).path("mfaChallenge").asText();
        assertEquals(200, call(g, "POST", "/admin/login/mfa", Map.of("challenge", c3, "code", codes.get(0).toLowerCase())).getResponse().getStatus(), "備援碼不分大小寫");
        String c4 = data(password(guest(), "shop_admin", pw)).path("mfaChallenge").asText();
        assertEquals(401, call(g, "POST", "/admin/login/mfa", Map.of("challenge", c4, "code", codes.get(0))).getResponse().getStatus(), "備援碼只能用一次");
        var me = new Client(g.csrf(), List.of(g.cookies().get(0), cookie(ok2, "accessToken"), cookie(ok2, "refreshToken")), String.valueOf(id("shop_admin")));
        assertEquals(9, data(ok(me, "GET", "/admin/mfa", null, 200)).path("backupCodesRemaining").asInt());
        assertEquals(400, call(me, "POST", "/admin/mfa/backup-codes", Map.of("code", "12345")).getResponse().getStatus(), "重新產生需要正確 TOTP");
        assertEquals(400, call(me, "POST", "/admin/mfa/backup-codes", Map.of("code", codes.get(1))).getResponse().getStatus(), "重新產生不接受備援碼，必須重新驗證 TOTP");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM staff_mfa_event WHERE user_id=? AND action='BACKUP_USED'", Integer.class, id("shop_admin")) >= 1 ? 1 : 0);
        assertEquals(404, call(me, "POST", "/admin/mfa/disable", Map.of("code", "123456")).getResponse().getStatus(), "強制 MFA 沒有自行停用");

        // 併發錯誤：每個 challenge 原子扣次數（≤5），失敗稽核有保存；帳號層級上限後連正確碼也 429
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM staff_mfa_event WHERE user_id=? AND action='LOGIN_FAIL'", Integer.class, id("shop_admin"));
        String c5 = data(password(guest(), "shop_admin", pw)).path("mfaChallenge").asText();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 8; i++) { String bad = "00000" + i; futures.add(pool.submit(() -> call(g, "POST", "/admin/login/mfa", Map.of("challenge", c5, "code", bad)).getResponse().getStatus())); }
            for (var f : futures) assertEquals(401, f.get());
        } finally { pool.shutdown(); }
        int after = jdbc.queryForObject("SELECT COUNT(*) FROM staff_mfa_event WHERE user_id=? AND action='LOGIN_FAIL'", Integer.class, id("shop_admin"));
        assertTrue(after - before >= 1 && after - before <= 5, "失敗稽核：" + (after - before));
        boolean capped = false;
        for (int i = 0; i < 4 && !capped; i++) {
            String c = data(password(guest(), "shop_admin", pw)).path("mfaChallenge").asText();
            for (int j = 0; j < 5 && !capped; j++) capped = call(g, "POST", "/admin/login/mfa", Map.of("challenge", c, "code", "99999" + j)).getResponse().getStatus() == 429;
        }
        assertTrue(capped, "帳號層級節流");
        String c6 = data(password(guest(), "shop_admin", pw)).path("mfaChallenge").asText();
        assertEquals(429, call(g, "POST", "/admin/login/mfa", Map.of("challenge", c6, "code", StaffLogin.totp(secret, step() + 1))).getResponse().getStatus());
    }

    @Test void regeneratingBackupCodesNeedsTotpInvalidatesOldCodesAndAdminMustReconfirm() throws Exception {
        clearMfa("shop_admin");
        var g = guest(); String pw = System.getenv("DEMO_ADMIN_PASSWORD");
        String token = data(password(g, "shop_admin", pw)).path("mfaSetupToken").asText();
        String secret = data(ok(g, "POST", "/admin/login/mfa/setup", Map.of("setupToken", token), 200)).path("secret").asText();
        var enrolled = call(g, "POST", "/admin/login/mfa/enroll", Map.of("setupToken", token, "code", StaffLogin.totp(secret, step())));
        assertEquals(200, enrolled.getResponse().getStatus(), text(enrolled));
        var old = new ArrayList<String>(); data(enrolled).path("backupCodes").forEach(c -> old.add(c.asText()));
        var me = new Client(g.csrf(), List.of(g.cookies().get(0), cookie(enrolled, "accessToken"), cookie(enrolled, "refreshToken")), String.valueOf(id("shop_admin")));
        ok(me, "POST", "/admin/mfa/backup-codes/ack", Map.of("downloaded", true, "confirmed", true), 200);
        assertEquals(400, call(me, "POST", "/admin/mfa/backup-codes", Map.of("code", old.get(0))).getResponse().getStatus(), "重新產生不接受備援碼，必須重新驗證 TOTP");
        var regen = data(ok(me, "POST", "/admin/mfa/backup-codes", Map.of("code", StaffLogin.totp(secret, step() + 1)), 200));
        assertEquals(10, regen.path("codes").size()); assertTrue(regen.path("required").asBoolean()); assertEquals("shop_admin", regen.path("username").asText());
        assertFalse(regen.path("generatedAt").isMissingNode());
        assertEquals(403, call(me, "GET", "/admin/reports/low-stock", null).getResponse().getStatus(), "重新產生後管理員需再次確認保存");
        ok(me, "POST", "/admin/mfa/backup-codes/ack", Map.of("downloaded", true, "confirmed", true), 200);
        assertEquals(200, call(me, "GET", "/admin/reports/low-stock", null).getResponse().getStatus());
        String c = data(password(guest(), "shop_admin", pw)).path("mfaChallenge").asText();
        assertEquals(401, call(g, "POST", "/admin/login/mfa", Map.of("challenge", c, "code", old.get(1))).getResponse().getStatus(), "舊備援碼立即失效");
        assertEquals(200, call(g, "POST", "/admin/login/mfa", Map.of("challenge", c, "code", regen.path("codes").get(0).asText())).getResponse().getStatus(), "新備援碼可用");
        assertEquals(10, jdbc.queryForObject("SELECT COUNT(*) FROM staff_mfa_backup_code WHERE user_id=?", Integer.class, id("shop_admin")), "只保留新的一組雜湊");
    }

    @Test void sessionsWithoutMfaClaimAreRejectedAndCustomersUnaffected() throws Exception {
        var a = admin();
        int adminId = Integer.parseInt(a.id());
        int auth = jdbc.queryForObject("SELECT auth_version FROM user WHERE id=?", Integer.class, adminId);
        // 模擬部署前（無 MFA 標記）發出的 session：Redis 仍有對應 jti，也必須被拒
        String device = uuid(), jti = uuid();
        var legacy = new HashMap<String, Object>(Map.of("id", adminId, "username", "shop_admin", "deviceId", device, "jti", jti, "rememberMe", false, "realm", "staff", "authVersion", auth));
        redis.opsForValue().set("shop:session:" + adminId + ":" + device, jti); sessions.add("shop:session:" + adminId + ":" + device);
        var old = new Client(a.csrf(), List.of(a.cookies().get(0), new Cookie("accessToken", jwt.genAccessToken(legacy)), new Cookie("refreshToken", jwt.genRefreshToken(legacy))), a.id());
        assertEquals(401, call(old, "GET", "/admin/reports/low-stock", null).getResponse().getStatus(), "沒有 MFA 標記的 access token 無效");
        assertEquals(401, call(old, "POST", "/auth/refresh", null).getResponse().getStatus(), "也不能用 refresh 換新 session");
        assertEquals(200, call(a, "GET", "/admin/reports/low-stock", null).getResponse().getStatus(), "完成 MFA 的 session 正常");
        // 顧客不受影響：密碼登入即可
        var c = customer();
        assertEquals(200, call(c, "GET", "/customer/me", null).getResponse().getStatus());
    }

    @Test void adminResetRevokesSessionsAndForcesReEnrollment() throws Exception {
        if (System.getenv("DEMO_STAFF_PASSWORD") == null) return;
        var a = admin();
        var g = guest(); String device = uuid();
        var r = StaffLogin.complete(mvc, () -> mvc.perform(post("/figura/api/admin/login").contextPath("/figura").servletPath("/api/admin/login").cookie(g.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN", g.csrf())
                .param("username", "shop_staff").param("password", System.getenv("DEMO_STAFF_PASSWORD")).param("deviceId", device)).andReturn());
        sessions.add("shop:session:" + data(r).path("id").asText() + ":" + device);
        var s = new Client(g.csrf(), List.of(g.cookies().get(0), cookie(r, "accessToken"), cookie(r, "refreshToken")), data(r).path("id").asText());
        int staffId = id("shop_staff");
        assertEquals(200, call(s, "GET", "/admin/reports/low-stock", null).getResponse().getStatus());
        assertEquals(403, call(s, "POST", "/admin/access/staff/" + id("shop_admin") + "/mfa/reset", Map.of("requestId", uuid(), "note", "x")).getResponse().getStatus(), "一般員工不能重設");
        assertEquals(409, call(a, "POST", "/admin/access/staff/" + a.id() + "/mfa/reset", Map.of("requestId", uuid(), "note", "自己")).getResponse().getStatus(), "不能重設自己");
        var body = Map.of("requestId", uuid(), "note", "員工遺失手機與備援碼");
        assertFalse(data(ok(a, "POST", "/admin/access/staff/" + staffId + "/mfa/reset", body, 200)).path("replayed").asBoolean());
        assertTrue(data(ok(a, "POST", "/admin/access/staff/" + staffId + "/mfa/reset", body, 200)).path("replayed").asBoolean(), "同 requestId 重送");
        assertEquals(401, call(s, "GET", "/admin/reports/low-stock", null).getResponse().getStatus(), "重設後既有 session 立即失效");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM staff_mfa WHERE user_id=?", Integer.class, staffId));
        assertTrue(data(password(guest(), "shop_staff", System.getenv("DEMO_STAFF_PASSWORD"))).path("mfaSetupRequired").asBoolean(), "下次登入重新綁定");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM staff_mfa_event WHERE id=? AND user_id=? AND action='RESET' AND actor_id=?", Integer.class, body.get("requestId"), staffId, Integer.parseInt(a.id())), "重設稽核");
    }

    @Test void resetInvalidatesPendingTokensAndBackupCodeGuessingIsLimited() throws Exception {
        String pw = System.getenv("DEMO_STAFF_PASSWORD"); if (pw == null) return;
        var a = admin(); clearMfa("shop_staff"); int staffId = id("shop_staff");
        var g = guest();
        String tokenA = data(password(g, "shop_staff", pw)).path("mfaSetupToken").asText(), tokenB = data(password(g, "shop_staff", pw)).path("mfaSetupToken").asText();
        String secret = data(ok(g, "POST", "/admin/login/mfa/setup", Map.of("setupToken", tokenA), 200)).path("secret").asText();
        var enrolled = call(g, "POST", "/admin/login/mfa/enroll", Map.of("setupToken", tokenA, "code", StaffLogin.totp(secret, step())));
        assertEquals(200, enrolled.getResponse().getStatus(), text(enrolled));
        assertFalse(data(enrolled).path("backupCodesRequired").asBoolean(), "一般員工可略過保存");
        assertEquals(409, call(g, "POST", "/admin/login/mfa/setup", Map.of("setupToken", tokenB)).getResponse().getStatus(), "已綁定後另一枚綁定 token 不能重設密鑰");
        var s = new Client(g.csrf(), List.of(g.cookies().get(0), cookie(enrolled, "accessToken"), cookie(enrolled, "refreshToken")), String.valueOf(staffId));
        assertEquals(200, call(s, "GET", "/admin/reports/low-stock", null).getResponse().getStatus(), "一般員工綁定後不需確認即可使用");
        assertFalse(data(ok(s, "POST", "/admin/mfa/backup-codes/ack", Map.of("downloaded", false, "confirmed", false), 200)).path("backupAckPending").asBoolean(), "略過只寫稽核");
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM staff_mfa_event WHERE user_id=? AND action='BACKUP_SKIP'", Integer.class, staffId) >= 1);
        // 已登入 session 也不能無限猜碼重新產生備援碼
        boolean capped = false;
        for (int i = 0; i < 12 && !capped; i++) capped = call(s, "POST", "/admin/mfa/backup-codes", Map.of("code", "88888" + (i % 10))).getResponse().getStatus() == 429;
        assertTrue(capped, "備援碼端點有帳號層級上限");
        redis.delete("shop:mfa:fail:" + staffId);
        // 進行中的 challenge 在重設後失效，即使驗證碼正確
        String challenge = data(password(guest(), "shop_staff", pw)).path("mfaChallenge").asText();
        ok(a, "POST", "/admin/access/staff/" + staffId + "/mfa/reset", Map.of("requestId", uuid(), "note", "測試重設"), 200);
        assertEquals(401, call(g, "POST", "/admin/login/mfa", Map.of("challenge", challenge, "code", StaffLogin.totp(secret, step() + 1))).getResponse().getStatus(), "重設前的 challenge 失效");
        assertEquals(401, call(g, "POST", "/admin/login/mfa/setup", Map.of("setupToken", tokenB)).getResponse().getStatus(), "重設前的綁定 token 失效");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM staff_mfa WHERE user_id=?", Integer.class, staffId), "沒有人能用舊 token 搶先重新綁定");
    }

    @Test void reportsSeparateMockFromRealAndListLowStock() throws Exception {
        var a = admin(); var f = fixture(a, 1, 777);
        var sales = data(ok(a, "GET", "/admin/reports/sales", null, 200));
        assertEquals(0, sales.path("realRevenueTwd").asLong(), "沒有正式金流");
        boolean mock = false; for (var r : sales.path("revenue")) { assertFalse(r.path("real").asBoolean()); mock |= r.path("label").asText().contains("非真實營收"); }
        assertTrue(mock); assertTrue(sales.path("note").asText().contains("不可作為營收"));
        assertEquals(400, call(a, "GET", "/admin/reports/sales?from=2026-01-10&to=2026-01-01", null).getResponse().getStatus());
        boolean found = false; for (var r : data(ok(a, "GET", "/admin/reports/low-stock?threshold=1", null, 200))) found |= r.path("skuId").asText().equals(f.sku());
        assertTrue(found);
        var status = data(ok(a, "GET", "/admin/ops/status", null, 200));
        assertTrue(status.path("database").asBoolean()); assertTrue(status.path("redis").asBoolean()); assertTrue(status.has("serverErrors"));
        assertTrue(status.path("mfaAvailable").asBoolean()); assertTrue(status.path("staffMfaEnrolled").asLong() >= 1);
        if (System.getenv("DEMO_STAFF_PASSWORD") != null) {
            var c = guest(); String device = uuid();
            var r = StaffLogin.complete(mvc, () -> mvc.perform(post("/figura/api/admin/login").contextPath("/figura").servletPath("/api/admin/login").cookie(c.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN", c.csrf())
                    .param("username", "shop_staff").param("password", System.getenv("DEMO_STAFF_PASSWORD")).param("deviceId", device)).andReturn());
            sessions.add("shop:session:" + data(r).path("id").asText() + ":" + device);
            var s = new Client(c.csrf(), List.of(c.cookies().get(0), cookie(r, "accessToken"), cookie(r, "refreshToken")), data(r).path("id").asText());
            assertEquals(403, call(s, "GET", "/admin/reports/sales", null).getResponse().getStatus(), "一般員工看不到營收");
            assertEquals(200, call(s, "GET", "/admin/reports/low-stock", null).getResponse().getStatus());
        }
    }
}
