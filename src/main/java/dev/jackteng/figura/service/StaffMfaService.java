package dev.jackteng.figura.service;

import dev.jackteng.figura.pojo.CustomUserDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
 * 員工後台強制雙因素（TOTP，RFC 6238：SHA1／30 秒／6 位數，允許前後一個時間窗）。
 * <ul>
 * <li>所有員工（含系統管理員、店長）都必須綁定；顧客不受影響。</li>
 * <li>密碼正確後：未綁定者取得 15 分鐘的「綁定 token」，已綁定者取得 5 分鐘的「驗證 challenge」。兩者都不是 session；
 *     完成綁定或驗證後才由 {@link AdminSessionService} 發出帶 MFA 標記的 session。</li>
 * <li>MFA_ENCRYPTION_KEY 未設定時拒絕員工登入（503），不降級為單因素。</li>
 * <li>每次嘗試先以 Redis 原子扣次數：每個 token／challenge 最多 5 次、帳號 15 分鐘最多 10 次；成功時 DEL 原子消耗。</li>
 * <li>同一時間窗的 TOTP 只能用一次；備援碼 10 組、一次性，只存 HMAC。</li>
 * <li>鎖序：員工列 → MFA 列；重設為治理寫入：guard → 依 ID 全部員工 → MFA 列。</li>
 * </ul>
 */
@Service
public class StaffMfaService {
    private static final String ISSUER = "Figura";
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5), SETUP_TTL = Duration.ofMinutes(15), ACCOUNT_WINDOW = Duration.ofMinutes(15);
    private static final int MAX_ACCOUNT_FAILURES = 10, BACKUP_CODES = 10;
    private static final String BACKUP_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final DefaultRedisScript<List> RESERVE = new DefaultRedisScript<>(
            "if redis.call('EXISTS',KEYS[1])==0 then return nil end; local t=redis.call('HINCRBY',KEYS[1],'tries',1); "
            + "if t>5 then redis.call('DEL',KEYS[1]); return nil end; return redis.call('HMGET',KEYS[1],'user','device','remember','auth')", List.class);
    private static final DefaultRedisScript<List> PEEK = new DefaultRedisScript<>(
            "if redis.call('EXISTS',KEYS[1])==0 then return nil end; return redis.call('HMGET',KEYS[1],'user','device','remember','auth')", List.class);
    private static final DefaultRedisScript<Long> ACCOUNT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]) end; return n", Long.class);

    private final JdbcTemplate db; private final StringRedisTemplate redis; private final byte[] key; private final byte[] hmacKey;
    private final TransactionTemplate tx; private final SecureRandom random = new SecureRandom();

    public StaffMfaService(JdbcTemplate db, StringRedisTemplate redis, @Value("${MFA_ENCRYPTION_KEY:}") String secret, PlatformTransactionManager tm) {
        this.db = db; this.redis = redis; this.tx = new TransactionTemplate(tm);
        byte[] k = null, h = null;
        if (secret != null && secret.length() >= 32) {
            try {
                var sha = MessageDigest.getInstance("SHA-256");
                k = sha.digest(secret.getBytes(StandardCharsets.UTF_8));
                h = sha.digest(("backup-codes|" + secret).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) { throw new IllegalStateException(e); }
        } else {
            org.slf4j.LoggerFactory.getLogger(StaffMfaService.class).error("MFA_ENCRYPTION_KEY 未設定或少於 32 字元：員工後台登入將被拒絕（強制雙因素，不降級為單因素）");
        }
        this.key = k; this.hmacKey = h;
    }
    public boolean available() { return key != null; }
    private static ResponseStatusException fail(int s, String m) { return new ResponseStatusException(HttpStatus.valueOf(s), m); }
    /** 強制 MFA：金鑰未設定時不得登入後台。 */
    public void requireAvailable() { if (!available()) throw fail(503, "後台雙因素驗證尚未設定，暫停員工登入，請聯絡系統管理者"); }

    /** backupAckPending：需完成備援碼保存確認才能使用業務 API；backupCodesRequired：此員工必須保存備援碼（具 access.mfa.reset）。 */
    public record Status(boolean available, boolean enabled, int backupCodesRemaining, boolean backupAckPending, boolean backupCodesRequired) {}
    /** 新產生的備援碼：只在這次回應出現一次；伺服器只保存雜湊。 */
    /** batchId：這一批備援碼的識別碼；保存確認必須帶回，舊批次不能確認新批次。 */
    public record BackupCodes(List<String> codes, String username, java.time.OffsetDateTime generatedAt, boolean required, String batchId) {}
    public record Setup(String secret, String otpauthUri) {}
    /** authVersion：簽發 token 當下的 user.auth_version；重設會遞增，舊流程因此失效。 */
    public record Pending(int user, String username, String device, boolean remember, int authVersion) {}
    public record Enrolled(Pending pending, BackupCodes backup) {}

    @Transactional(readOnly = true)
    public Status status(int user) {
        return new Status(available(), enabled(user), db.queryForObject("SELECT COUNT(*) FROM staff_mfa_backup_code WHERE user_id=? AND used_at IS NULL", Integer.class, user),
                ackPending(user), backupRequired(user));
    }
    /** 必須保存備援碼的員工：具 access.mfa.reset（系統管理員／店長）。依權限判斷，不寫死帳號。 */
    public boolean backupRequired(int user) {
        Integer n = db.queryForObject("SELECT COUNT(*) FROM user_role ur JOIN role r ON r.id=ur.role_id AND r.enabled=TRUE JOIN role_permission rp ON rp.role_id=r.id"
                + " JOIN permissions_backend p ON p.id=rp.permission_id WHERE ur.user_id=? AND p.code='access.mfa.reset'", Integer.class, user);
        return n != null && n > 0;
    }
    /**
     * 是否需要完成保存確認：依「目前」權限即時判斷——具 access.mfa.reset 且已確認批次 ≠ 目前批次。
     * 員工被升權後即使先前略過，也會立即要求（需重新產生、下載並確認）。
     */
    public boolean ackPending(int user) {
        var rows = db.queryForList("SELECT backup_batch_id,backup_confirmed_batch_id FROM staff_mfa WHERE user_id=? AND enabled=TRUE", user);
        if (rows.isEmpty()) return false;
        var r = rows.get(0);
        return !Objects.equals(r.get("backup_batch_id"), r.get("backup_confirmed_batch_id")) && backupRequired(user);
    }
    /** 產生新的一批備援碼（呼叫端已持有員工列與 MFA 列鎖）；舊批次與其確認一併失效。 */
    private BackupCodes issueBackupCodes(int user) {
        var codes = newBackupCodes(user);
        String batch = UUID.randomUUID().toString();
        db.update("UPDATE staff_mfa SET backup_batch_id=? WHERE user_id=?", batch, user);
        var at = db.queryForObject("SELECT CURRENT_TIMESTAMP(6)", java.time.LocalDateTime.class);
        return new BackupCodes(codes, db.queryForObject("SELECT username FROM user WHERE id=?", String.class, user), at.atOffset(java.time.ZoneOffset.ofHours(8)), backupRequired(user), batch);
    }
    /**
     * 備援碼保存確認。伺服器無法驗證檔案是否確實保存：只記錄使用者在頁面上「已觸發下載並勾選確認」或「略過」。
     * 必須保存者（待確認中）只能以 downloaded=true 且 confirmed=true 解除；其他員工可略過（只寫稽核）。
     */
    @Transactional
    public Status acknowledge(int user, String batchId, boolean downloaded, boolean confirmed) {
        var row = lock(user, null);
        if (row == null || !truthy(row.get("enabled"))) throw fail(409, "尚未完成雙因素綁定");
        if (batchId == null || !batchId.equals(row.get("backup_batch_id"))) throw fail(409, "備援碼已重新產生，這份已失效；請以最新一組確認");
        boolean required = backupRequired(user), saved = downloaded && confirmed;
        if (required && !saved) throw fail(400, "請先下載備援碼並勾選已妥善保存");
        if (saved) db.update("UPDATE staff_mfa SET backup_confirmed_batch_id=? WHERE user_id=? AND backup_batch_id=?", batchId, user, batchId);
        event(user, downloaded ? "BACKUP_ACK" : "BACKUP_SKIP", null,
                (downloaded ? "頁面已觸發下載" + (confirmed ? "並勾選保存確認" : "") + "（無法驗證檔案是否確實保存）" : "使用者選擇略過下載") + "；批次 " + batchId);
        return status(user);
    }
    public boolean enabled(int user) {
        var rows = db.queryForList("SELECT enabled FROM staff_mfa WHERE user_id=?", user);
        return !rows.isEmpty() && truthy(rows.get(0).get("enabled"));
    }

    // ---- 登入：challenge（已綁定）與綁定 token（未綁定） ----
    public String challenge(int user, int authVersion, String device, boolean remember) { return token("shop:mfa:challenge:", user, authVersion, device, remember, CHALLENGE_TTL); }
    public String setupToken(int user, int authVersion, String device, boolean remember) { return token("shop:mfa:setup:", user, authVersion, device, remember, SETUP_TTL); }
    private String token(String prefix, int user, int authVersion, String device, boolean remember, Duration ttl) {
        String id = UUID.randomUUID().toString(), k = prefix + id;
        redis.opsForHash().putAll(k, Map.of("user", String.valueOf(user), "device", device, "remember", String.valueOf(remember), "auth", String.valueOf(authVersion), "tries", "0"));
        redis.expire(k, ttl);
        return id;
    }
    private static String valid(String token) {
        if (token == null || !token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw fail(401, "驗證已過期，請重新登入");
        return token;
    }
    /** 帳號層級額度：核對前原子保留一次嘗試，超過上限 429。 */
    private void reserveAccount(int user, String tokenKey) {
        Long used = redis.execute(ACCOUNT, List.of("shop:mfa:fail:" + user), String.valueOf(ACCOUNT_WINDOW.toSeconds()));
        if (used == null || used > MAX_ACCOUNT_FAILURES) { if (tokenKey != null) redis.delete(tokenKey); throw fail(429, "驗證錯誤次數過多，請 15 分鐘後再試"); }
    }
    private void failed(int user) { tx.executeWithoutResult(st -> event(user, "LOGIN_FAIL", null, null)); }

    /** 綁定第一步：以綁定 token 產生（或重新產生）待確認的密鑰。不計入嘗試次數，但 token 必須有效。 */
    public Setup setup(String setupToken) {
        requireAvailable();
        String k = "shop:mfa:setup:" + valid(setupToken);
        @SuppressWarnings("unchecked") List<Object> p = redis.execute(PEEK, List.of(k));
        if (p == null || p.isEmpty() || p.get(0) == null) throw fail(401, "驗證已過期，請重新登入");
        int user = Integer.parseInt(String.valueOf(p.get(0))), auth = authOf(p);
        return tx.execute(st -> {
            var row = lock(user, auth);
            if (row != null && truthy(row.get("enabled"))) throw fail(409, "已完成雙因素綁定，請重新登入");
            byte[] raw = new byte[20]; random.nextBytes(raw);
            if (row == null) db.update("INSERT INTO staff_mfa(user_id,secret_enc,enabled) VALUES(?,?,FALSE)", user, encrypt(raw));
            else db.update("UPDATE staff_mfa SET secret_enc=?,confirmed_at=NULL,last_step=0 WHERE user_id=? AND enabled=FALSE", encrypt(raw), user);
            event(user, "SETUP", null, null);
            String username = db.queryForObject("SELECT username FROM user WHERE id=?", String.class, user), b32 = base32(raw);
            String label = URLEncoder.encode(ISSUER + ":" + username, StandardCharsets.UTF_8).replace("+", "%20");
            return new Setup(b32, "otpauth://totp/" + label + "?secret=" + b32 + "&issuer=" + ISSUER + "&algorithm=SHA1&digits=6&period=30");
        });
    }

    /** 綁定第二步：驗證碼正確 → 啟用、產生備援碼、消耗 token。之後由呼叫端發 session。 */
    public Enrolled enroll(String setupToken, String code) {
        requireAvailable();
        String k = "shop:mfa:setup:" + valid(setupToken);
        @SuppressWarnings("unchecked") List<Object> p = redis.execute(RESERVE, List.of(k));
        if (p == null || p.isEmpty() || p.get(0) == null) throw fail(401, "驗證已過期，請重新登入");
        int user = Integer.parseInt(String.valueOf(p.get(0))), auth = authOf(p);
        reserveAccount(user, k);
        BackupCodes codes = tx.execute(st -> {
            var row = lock(user, auth);
            if (row == null || truthy(row.get("enabled")) || !totp(user, row, code)) return null;
            db.update("UPDATE staff_mfa SET enabled=TRUE,confirmed_at=CURRENT_TIMESTAMP(6) WHERE user_id=?", user);
            var out = issueBackupCodes(user);
            event(user, "ENROLL", null, null);
            return out;
        });
        if (codes == null) { failed(user); throw fail(401, "驗證碼不正確，請確認驗證器 App 的時間與帳號"); }
        if (!Boolean.TRUE.equals(redis.delete(k))) throw fail(401, "驗證已過期，請重新登入");
        redis.delete("shop:mfa:fail:" + user);
        return new Enrolled(pending(user, p), codes);
    }

    /** 已綁定員工的登入驗證：接受 6 位數 TOTP 或一次性備援碼。 */
    public Pending verify(String challenge, String code) {
        requireAvailable();
        String k = "shop:mfa:challenge:" + valid(challenge);
        @SuppressWarnings("unchecked") List<Object> p = redis.execute(RESERVE, List.of(k));
        if (p == null || p.isEmpty() || p.get(0) == null) throw fail(401, "驗證已過期，請重新登入");
        int user = Integer.parseInt(String.valueOf(p.get(0))), auth = authOf(p);
        reserveAccount(user, k);
        String used = tx.execute(st -> {
            var row = lock(user, auth);
            if (row == null || !truthy(row.get("enabled"))) return null;
            if (totp(user, row, code)) return "TOTP";
            if (backup(user, code)) { event(user, "BACKUP_USED", null, null); return "BACKUP"; }
            return null;
        });
        if (used == null) { failed(user); throw fail(401, "驗證碼不正確"); }
        if (!Boolean.TRUE.equals(redis.delete(k))) throw fail(401, "驗證已過期，請重新登入");   // 被另一個併發請求消耗
        redis.delete("shop:mfa:fail:" + user);
        tx.executeWithoutResult(st -> event(user, "LOGIN_OK", null, null));
        return pending(user, p);
    }
    private Pending pending(int user, List<Object> p) {
        return new Pending(user, db.queryForObject("SELECT username FROM user WHERE id=?", String.class, user), String.valueOf(p.get(1)), Boolean.parseBoolean(String.valueOf(p.get(2))), authOf(p));
    }
    private static int authOf(List<Object> p) {
        try { return Integer.parseInt(String.valueOf(p.get(3))); } catch (RuntimeException e) { throw fail(401, "驗證已過期，請重新登入"); }
    }

    // ---- 已登入員工：重新產生備援碼（需目前的 TOTP） ----
    public BackupCodes regenerateBackupCodes(int user, String code) {
        requireAvailable();
        reserveAccount(user, null);   // 與登入共用帳號層級額度，避免以 session 無限猜碼
        BackupCodes out = tx.execute(st -> {
            var row = lock(user, null);
            if (row == null || !truthy(row.get("enabled"))) throw fail(409, "尚未完成雙因素綁定");
            if (!totp(user, row, code)) return null;   // 重新產生必須重新驗證 TOTP（不接受備援碼）
            var codes = issueBackupCodes(user);   // 舊備援碼在同一交易刪除，立即失效
            event(user, "BACKUP_REGEN", null, null);
            return codes;
        });
        if (out == null) { failed(user); throw fail(400, "驗證碼不正確"); }
        redis.delete("shop:mfa:fail:" + user);
        return out;
    }

    /**
     * 管理員重設其他員工的 MFA（遺失驗證器與備援碼）：刪除密鑰與備援碼、撤銷對方所有 session（auth_version+1），
     * 對方下次登入需重新綁定。不可重設自己。requestId 冪等。
     */
    @Transactional
    public boolean reset(int target, String requestId, String note, CustomUserDetails actor) {
        String n = note == null ? "" : note.trim();
        if (n.isEmpty() || n.length() > 500) throw fail(400, "請填寫重設原因（500 字內）");
        if (actor == null) throw fail(403, "需要登入");
        db.queryForObject("SELECT id FROM staff_access_guard WHERE id=1 FOR UPDATE", Integer.class);
        db.queryForList("SELECT id FROM user ORDER BY id FOR UPDATE", Integer.class);
        var prior = db.queryForList("SELECT user_id,actor_id,action,note FROM staff_mfa_event WHERE id=?", requestId);
        if (!prior.isEmpty()) {
            var e = prior.get(0);
            if (!"RESET".equals(e.get("action")) || ((Number) e.get("user_id")).intValue() != target || !Objects.equals(((Number) e.get("actor_id")).intValue(), actor.getId()) || !n.equals(e.get("note")))
                throw fail(409, "操作編號已用於不同內容");
            return true;
        }
        Integer granted = db.queryForObject("SELECT COUNT(*) FROM user u JOIN user_role ur ON ur.user_id=u.id JOIN role r ON r.id=ur.role_id AND r.enabled=TRUE JOIN role_permission rp ON rp.role_id=r.id"
                + " JOIN permissions_backend p ON p.id=rp.permission_id WHERE u.id=? AND u.is_stop='F' AND p.code='access.mfa.reset'", Integer.class, actor.getId());
        if (granted == null || granted == 0) throw fail(403, "需要重設員工雙因素權限");
        if (target == actor.getId()) throw fail(409, "不能重設自己的雙因素，請使用備援碼登入或請其他管理員協助");
        if (db.queryForObject("SELECT COUNT(*) FROM user WHERE id=?", Integer.class, target) == 0) throw fail(404, "找不到員工");
        db.queryForList("SELECT user_id FROM staff_mfa WHERE user_id=? FOR UPDATE", Integer.class, target);
        db.update("DELETE FROM staff_mfa_backup_code WHERE user_id=?", target);
        db.update("DELETE FROM staff_mfa WHERE user_id=?", target);
        db.update("UPDATE user SET auth_version=auth_version+1 WHERE id=?", target);   // 撤銷既有 session
        try { db.update("INSERT INTO staff_mfa_event(id,user_id,action,actor_id,note) VALUES(?,?,'RESET',?,?)", requestId, target, actor.getId(), n); }
        catch (DuplicateKeyException e) { throw fail(409, "操作編號已被使用"); }
        return false;
    }

    // ---- 內部 ----
    /** 鎖序：員工列 → MFA 列。expectedAuth 非 null 時核對簽發 token 時的 auth_version（重設、停用、權限變更後舊流程失效）。 */
    private Map<String, Object> lock(int user, Integer expectedAuth) {
        var auth = db.queryForList("SELECT auth_version FROM user WHERE id=? AND is_stop='F' FOR UPDATE", Integer.class, user);
        if (auth.isEmpty() || (expectedAuth != null && !expectedAuth.equals(auth.get(0)))) throw fail(401, "驗證已過期，請重新登入");
        var rows = db.queryForList("SELECT * FROM staff_mfa WHERE user_id=? FOR UPDATE", user);
        return rows.isEmpty() ? null : rows.get(0);
    }
    private boolean totp(int user, Map<String, Object> row, String code) {
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
    /** 備援碼：格式 XXXXX-XXXXX（不分大小寫、可省略連字號），原子標記已使用。 */
    private boolean backup(int user, String code) {
        if (code == null) return false;
        String c = code.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        if (!c.matches("[" + BACKUP_ALPHABET + "]{10}")) return false;
        return db.update("UPDATE staff_mfa_backup_code SET used_at=CURRENT_TIMESTAMP(6) WHERE user_id=? AND code_hash=? AND used_at IS NULL", user, hmac(c)) == 1;
    }
    private List<String> newBackupCodes(int user) {
        db.update("DELETE FROM staff_mfa_backup_code WHERE user_id=?", user);
        var out = new ArrayList<String>();
        while (out.size() < BACKUP_CODES) {
            var b = new StringBuilder();
            for (int i = 0; i < 10; i++) b.append(BACKUP_ALPHABET.charAt(random.nextInt(BACKUP_ALPHABET.length())));
            String c = b.toString();
            if (db.update("INSERT IGNORE INTO staff_mfa_backup_code(user_id,code_hash) VALUES(?,?)", user, hmac(c)) == 1) out.add(c.substring(0, 5) + "-" + c.substring(5));
        }
        return out;
    }
    private String hmac(String value) {
        try { var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(hmacKey, "HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.US_ASCII))); }
        catch (Exception e) { throw new IllegalStateException(e); }
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
    private void event(int user, String action, Integer actor, String note) {
        db.update("INSERT INTO staff_mfa_event(id,user_id,action,actor_id,note) VALUES(?,?,?,?,?)", UUID.randomUUID().toString(), user, action, actor, note);
    }
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
    private static boolean truthy(Object v) { return v instanceof Boolean b ? b : v instanceof Number n && n.intValue() != 0; }
}
