package dev.jackteng.figura;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 測試用：員工後台強制雙因素後，密碼登入不再直接發 session。這個 helper 走完正式流程（綁定 token → 產生密鑰 → TOTP 綁定）
 * 並回傳發出 session 的回應，讓既有測試照舊取 Cookie 與 data.id。
 * TOTP 同一時間窗只能用一次，測試又頻繁登入，所以若帳號已綁定，會先在「隔離測試 DB」清掉該員工的 MFA 再重新綁定。
 * 這只存在於測試程式碼，正式程式沒有任何略過 MFA 的路徑。
 */
final class StaffLogin {
    private StaffLogin() {}
    interface Attempt { MvcResult run() throws Exception; }

    static MvcResult complete(MockMvc mvc, Attempt attempt) throws Exception {
        var ctx = mvc.getDispatcherServlet().getWebApplicationContext();
        var json = ctx.getBean(ObjectMapper.class); var jdbc = ctx.getBean(JdbcTemplate.class); var redis = ctx.getBean(StringRedisTemplate.class);
        var r = attempt.run();
        if (r.getResponse().getStatus() != 200) return r;
        JsonNode data = json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        if (data.path("mfaRequired").asBoolean()) {
            String challenge = "shop:mfa:challenge:" + data.path("mfaChallenge").asText();
            Object user = redis.opsForHash().get(challenge, "user");
            jdbc.update("DELETE FROM staff_mfa_backup_code WHERE user_id=?", user);
            jdbc.update("DELETE FROM staff_mfa WHERE user_id=?", user);
            redis.delete(challenge); redis.delete("shop:mfa:fail:" + user);
            r = attempt.run();
            if (r.getResponse().getStatus() != 200) return r;
            data = json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        }
        if (!data.path("mfaSetupRequired").asBoolean()) return r;
        String token = data.path("mfaSetupToken").asText();
        Cookie[] cookies = r.getRequest().getCookies(); String csrf = r.getRequest().getHeader("X-XSRF-TOKEN");
        var setup = mvc.perform(post("/figura/api/admin/login/mfa/setup").contextPath("/figura").servletPath("/api/admin/login/mfa/setup").cookie(cookies).header("X-XSRF-TOKEN", csrf)
                .contentType("application/json").content(json.writeValueAsBytes(Map.of("setupToken", token)))).andReturn();
        String secret = json.readTree(setup.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data").path("secret").asText();
        return mvc.perform(post("/figura/api/admin/login/mfa/enroll").contextPath("/figura").servletPath("/api/admin/login/mfa/enroll").cookie(cookies).header("X-XSRF-TOKEN", csrf)
                .contentType("application/json").content(json.writeValueAsBytes(Map.of("setupToken", token, "code", totp(secret, System.currentTimeMillis() / 1000 / 30))))).andReturn();
    }

    static String totp(String base32, long step) throws Exception {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; var out = new java.io.ByteArrayOutputStream(); int buffer = 0, bits = 0;
        for (char ch : base32.toCharArray()) { buffer = (buffer << 5) | alphabet.indexOf(ch); bits += 5; if (bits >= 8) { out.write((buffer >> (bits - 8)) & 0xff); bits -= 8; } }
        var mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(out.toByteArray(), "HmacSHA1"));
        byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array()); int o = h[h.length - 1] & 0xf;
        return String.format("%06d", (((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff)) % 1_000_000);
    }
}
