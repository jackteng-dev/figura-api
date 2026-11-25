package dev.jackteng.figura;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Commerce V2 Phase 7：員工 TOTP、營運報表（模擬與真實分開）、低庫存、營運狀態。 */
// MFA 測試金鑰在 src/test/resources/application.properties；屬性與其他測試相同以共用 context
@SpringBootTest(properties = "shop.orders.expiry-delay-ms=600000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "SHOP_MYSQL_INTEGRATION", matches = "true")
class OpsIntegrationTest extends ShopIntegrationSupport {
    @AfterEach void dropMfa() {
        Integer id = jdbc.queryForObject("SELECT id FROM user WHERE username='shop_admin'", Integer.class);
        jdbc.update("DELETE FROM staff_mfa WHERE user_id=?", id); redis.delete("shop:mfa:fail:" + id);
    }

    private static String totp(String base32, long step) throws Exception {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; var out = new java.io.ByteArrayOutputStream(); int buffer = 0, bits = 0;
        for (char ch : base32.toCharArray()) { buffer = (buffer << 5) | alphabet.indexOf(ch); bits += 5; if (bits >= 8) { out.write((buffer >> (bits - 8)) & 0xff); bits -= 8; } }
        var mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(out.toByteArray(), "HmacSHA1"));
        byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array()); int o = h[h.length - 1] & 0xf;
        return String.format("%06d", (((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff)) % 1_000_000);
    }
    private org.springframework.test.web.servlet.MvcResult login(Client c) throws Exception {
        return mvc.perform(post("/figura/api/admin/login").contextPath("/figura").servletPath("/api/admin/login").cookie(c.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN", c.csrf())
                .param("username", "shop_admin").param("password", System.getenv("DEMO_ADMIN_PASSWORD")).param("deviceId", uuid())).andReturn();
    }

    @Test void totpIsOptionalAndGatesSessionIssue() throws Exception {
        var a = admin();
        var st = data(ok(a, "GET", "/admin/mfa", null, 200));
        assertTrue(st.path("available").asBoolean()); assertFalse(st.path("enabled").asBoolean());
        var setup = data(ok(a, "POST", "/admin/mfa/setup", null, 200));
        String secret = setup.path("secret").asText();
        assertTrue(setup.path("otpauthUri").asText().startsWith("otpauth://totp/Figura"));
        assertNotEquals(secret, jdbc.queryForObject("SELECT secret_enc FROM staff_mfa WHERE user_id=?", String.class, Integer.parseInt(a.id())), "密鑰加密保存");
        long step = System.currentTimeMillis() / 1000 / 30;
        assertEquals(400, call(a, "POST", "/admin/mfa/confirm", Map.of("code", "000000".equals(totp(secret, step)) ? "111111" : "000000")).getResponse().getStatus());
        assertTrue(data(ok(a, "POST", "/admin/mfa/confirm", Map.of("code", totp(secret, step)), 200)).path("enabled").asBoolean());

        // 密碼正確只拿到 challenge，不發 session
        var g = guest(); var r = login(g);
        assertEquals(200, r.getResponse().getStatus());
        var body = data(r);
        assertTrue(body.path("mfaRequired").asBoolean()); assertTrue(r.getResponse().getHeaders("Set-Cookie").stream().noneMatch(h -> h.startsWith("accessToken=")));
        String challenge = body.path("mfaChallenge").asText();
        assertEquals(401, call(g, "POST", "/admin/login/mfa", Map.of("challenge", challenge, "code", totp(secret, step))).getResponse().getStatus(), "同一時間窗的驗證碼不能重用");
        var done = call(g, "POST", "/admin/login/mfa", Map.of("challenge", challenge, "code", totp(secret, step + 1)));
        assertEquals(200, done.getResponse().getStatus(), text(done));
        assertTrue(done.getResponse().getHeaders("Set-Cookie").stream().anyMatch(h -> h.startsWith("accessToken=")));
        assertEquals(401, call(g, "POST", "/admin/login/mfa", Map.of("challenge", challenge, "code", totp(secret, step + 1))).getResponse().getStatus(), "challenge 只能用一次");
        // 連錯 5 次 challenge 失效
        String c2 = data(login(guest())).path("mfaChallenge").asText();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM staff_mfa_event WHERE user_id=? AND action='LOGIN_FAIL'", Integer.class, Integer.parseInt(a.id()));
        // 併發送出 8 次錯誤驗證碼：challenge 次數原子計算，最多 5 次被核對
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 8; i++) { String wrong = "00000" + i; futures.add(pool.submit(() -> call(g, "POST", "/admin/login/mfa", Map.of("challenge", c2, "code", wrong)).getResponse().getStatus())); }
            for (var f : futures) assertEquals(401, f.get());
        } finally { pool.shutdown(); }
        int after = jdbc.queryForObject("SELECT COUNT(*) FROM staff_mfa_event WHERE user_id=? AND action='LOGIN_FAIL'", Integer.class, Integer.parseInt(a.id()));
        assertTrue(after - before <= 5 && after - before >= 1, "失敗稽核有保存且最多 5 次：" + (after - before));
        long now2 = System.currentTimeMillis() / 1000 / 30;
        assertEquals(401, call(g, "POST", "/admin/login/mfa", Map.of("challenge", c2, "code", totp(secret, now2 + 1))).getResponse().getStatus(), "用完次數的 challenge 不能再用");
        // 帳號層級上限：換新的 challenge 繼續猜，總嘗試超過 10 次後一律 429，正確驗證碼也不行
        boolean capped = false;
        for (int i = 0; i < 4 && !capped; i++) {
            String c = data(login(guest())).path("mfaChallenge").asText();
            for (int j = 0; j < 5 && !capped; j++) capped = call(g, "POST", "/admin/login/mfa", Map.of("challenge", c, "code", "99999" + j)).getResponse().getStatus() == 429;
        }
        assertTrue(capped, "帳號層級節流");
        String c3 = data(login(guest())).path("mfaChallenge").asText();
        assertEquals(429, call(g, "POST", "/admin/login/mfa", Map.of("challenge", c3, "code", totp(secret, System.currentTimeMillis() / 1000 / 30 + 1))).getResponse().getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM staff_mfa_event WHERE user_id=? AND action='LOGIN_OK'", Integer.class, Integer.parseInt(a.id())) >= 1 ? 1 : 0);
        assertEquals(400, call(a, "POST", "/admin/mfa/disable", Map.of("code", "999999".equals(totp(secret, step)) ? "888888" : "999999")).getResponse().getStatus());
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
        if (System.getenv("DEMO_STAFF_PASSWORD") != null) {
            var c = guest(); String device = uuid();
            var r = mvc.perform(post("/figura/api/admin/login").contextPath("/figura").servletPath("/api/admin/login").cookie(c.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN", c.csrf())
                    .param("username", "shop_staff").param("password", System.getenv("DEMO_STAFF_PASSWORD")).param("deviceId", device)).andReturn();
            sessions.add("shop:session:" + data(r).path("id").asText() + ":" + device);
            var s = new Client(c.csrf(), List.of(c.cookies().get(0), cookie(r, "accessToken"), cookie(r, "refreshToken")), data(r).path("id").asText());
            assertEquals(403, call(s, "GET", "/admin/reports/sales", null).getResponse().getStatus(), "一般員工看不到營收");
            assertEquals(200, call(s, "GET", "/admin/reports/low-stock", null).getResponse().getStatus());
        }
    }
}
