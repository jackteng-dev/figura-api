package dev.jackteng.figura.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

/**
 * Commerce V2 Phase 7：員工 TOTP 雙因素（RFC 6238，SHA1／30 秒／6 位數，允許前後一個時間窗）。
 * - 自願啟用：未啟用者登入流程不變。啟用後密碼正確只拿到 5 分鐘有效的一次性 challenge，輸入驗證碼才發 session。
 * - 密鑰以 AES-GCM 加密存 DB，金鑰取自 MFA_ENCRYPTION_KEY（私有環境，未設定時無法啟用 MFA）。
 * - 同一時間窗的驗證碼只能用一次（last_step）；challenge 最多 5 次錯誤。
 */
@Service
public class StaffMfaService {
    private static final String ISSUER = "Figura";
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    private final JdbcTemplate db; private final StringRedisTemplate redis; private final byte[] key; private final SecureRandom random = new SecureRandom();

    public StaffMfaService(JdbcTemplate db, StringRedisTemplate redis, @Value("${MFA_ENCRYPTION_KEY:}") String secret) {
        this.db = db; this.redis = redis;
        byte[] k = null;
        if (secret != null && secret.length() >= 32) {
            try { k = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)); } catch (Exception e) { throw new IllegalStateException(e); }
        }
        this.key = k;
    }
    public boolean available() { return key != null; }
    private static ResponseStatusException fail(int s, String m) { return new ResponseStatusException(HttpStatus.valueOf(s), m); }

    public record Status(boolean available, boolean enabled, boolean pendingSetup) {}
    public record Setup(String secret, String otpauthUri) {}

    @Transactional(readOnly = true)
    public Status status(int user) {
        var rows = db.queryForList("SELECT enabled FROM staff_mfa WHERE user_id=?", user);
        boolean enabled = !rows.isEmpty() && truthy(rows.get(0).get("enabled"));
        return new Status(available(), enabled, !rows.isEmpty() && !enabled);
    }
    public boolean enabled(int user) {
        var rows = db.queryForList("SELECT enabled FROM staff_mfa WHERE user_id=?", user);
        return !rows.isEmpty() && truthy(rows.get(0).get("enabled"));
    }

    /** 產生新的密鑰（尚未啟用）；已啟用者需先停用。 */
    @Transactional
    public Setup setup(int user, String username) {
        if (!available()) throw fail(404, "雙因素驗證尚未在此環境啟用");
        if (enabled(user)) throw fail(409, "已啟用雙因素驗證，請先停用再重新設定");
        byte[] raw = new byte[20]; random.nextBytes(raw);
        String b32 = base32(raw);
        db.update("INSERT INTO staff_mfa(user_id,secret_enc,enabled) VALUES(?,?,FALSE) ON DUPLICATE KEY UPDATE secret_enc=VALUES(secret_enc),enabled=FALSE,confirmed_at=NULL,last_step=0", user, encrypt(raw));
        event(user, "SETUP");
        String label = URLEncoder.encode(ISSUER + ":" + username, StandardCharsets.UTF_8).replace("+", "%20");
        return new Setup(b32, "otpauth://totp/" + label + "?secret=" + b32 + "&issuer=" + ISSUER + "&algorithm=SHA1&digits=6&period=30");
    }
    @Transactional
    public Status confirm(int user, String code) {
        var row = lock(user);
        if (row == null) throw fail(409, "請先產生雙因素密鑰");
        if (truthy(row.get("enabled"))) return status(user);
        if (!check(user, row, code)) throw fail(400, "驗證碼不正確");
        db.update("UPDATE staff_mfa SET enabled=TRUE,confirmed_at=CURRENT_TIMESTAMP(6) WHERE user_id=?", user);
        event(user, "ENABLE");
        return status(user);
    }
    @Transactional
    public Status disable(int user, String code) {
        var row = lock(user);
        if (row == null || !truthy(row.get("enabled"))) throw fail(409, "尚未啟用雙因素驗證");
        if (!check(user, row, code)) throw fail(400, "驗證碼不正確");
        db.update("DELETE FROM staff_mfa WHERE user_id=?", user);
        event(user, "DISABLE");
        return status(user);
    }

    // ---- 登入 challenge ----
    public String challenge(int user, String device, boolean remember) {
        String id = UUID.randomUUID().toString();
        redis.opsForValue().set("shop:mfa:challenge:" + id, user + "|" + device + "|" + remember + "|0", CHALLENGE_TTL);
        return id;
    }
    public record Pending(int user, String username, String device, boolean remember) {}
    /** 驗證 challenge＋驗證碼；成功後 challenge 立即失效。 */
    @Transactional
    public Pending verify(String challenge, String code) {
        if (challenge == null || !challenge.matches("[0-9a-f-]{36}")) throw fail(401, "驗證已過期，請重新登入");
        String k = "shop:mfa:challenge:" + challenge, v = redis.opsForValue().get(k);
        if (v == null) throw fail(401, "驗證已過期，請重新登入");
        String[] p = v.split("\\|");
        int user = Integer.parseInt(p[0]), tries = Integer.parseInt(p[3]);
        var row = lock(user);
        if (row == null || !truthy(row.get("enabled")) || !check(user, row, code)) {
            event(user, "LOGIN_FAIL");
            if (tries + 1 >= 5) redis.delete(k);
            else redis.opsForValue().set(k, p[0] + "|" + p[1] + "|" + p[2] + "|" + (tries + 1), CHALLENGE_TTL);
            throw fail(401, "驗證碼不正確");
        }
        redis.delete(k);
        event(user, "LOGIN_OK");
        return new Pending(user, db.queryForObject("SELECT username FROM user WHERE id=?", String.class, user), p[1], Boolean.parseBoolean(p[2]));
    }

    private Map<String, Object> lock(int user) {
        var rows = db.queryForList("SELECT * FROM staff_mfa WHERE user_id=? FOR UPDATE", user);
        return rows.isEmpty() ? null : rows.get(0);
    }
    private boolean check(int user, Map<String, Object> row, String code) {
        if (code == null || !code.matches("\\d{6}") || !available()) return false;
        byte[] secret = decrypt((String) row.get("secret_enc"));
        long now = System.currentTimeMillis() / 1000 / 30, last = ((Number) row.get("last_step")).longValue();
        for (long step = now - 1; step <= now + 1; step++) {
            if (step <= last) continue;   // 同一時間窗只用一次
            if (MessageDigest.isEqual(totp(secret, step).getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII))) {
                db.update("UPDATE staff_mfa SET last_step=? WHERE user_id=?", step, user);
                return true;
            }
        }
        return false;
    }
    static String totp(byte[] secret, long step) {
        try {
            var mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0xf;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void event(int user, String action) { db.update("INSERT INTO staff_mfa_event(id,user_id,action) VALUES(?,?,?)", UUID.randomUUID().toString(), user, action); }
    private String encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            var c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] out = c.doFinal(plain);
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + out.length).put(iv).put(out).array());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private byte[] decrypt(String value) {
        try {
            byte[] all = Base64.getDecoder().decode(value.substring(3));
            var c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, all, 0, 12));
            return c.doFinal(all, 12, all.length - 12);
        } catch (Exception e) { throw new IllegalStateException("MFA secret cannot be decrypted"); }
    }
    static String base32(byte[] data) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; var out = new StringBuilder(); int buffer = 0, bits = 0;
        for (byte b : data) { buffer = (buffer << 8) | (b & 0xff); bits += 8; while (bits >= 5) { out.append(alphabet.charAt((buffer >> (bits - 5)) & 31)); bits -= 5; } }
        if (bits > 0) out.append(alphabet.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }
    static byte[] base32Decode(String s) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; var out = new java.io.ByteArrayOutputStream(); int buffer = 0, bits = 0;
        for (char ch : s.toCharArray()) { buffer = (buffer << 5) | alphabet.indexOf(ch); bits += 5; if (bits >= 8) { out.write((buffer >> (bits - 8)) & 0xff); bits -= 8; } }
        return out.toByteArray();
    }
    private static boolean truthy(Object v) { return v instanceof Boolean b ? b : v instanceof Number n && n.intValue() != 0; }
}
