package dev.jackteng.figura.service;

import dev.jackteng.figura.config.SandboxMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 公開沙盒「MFA 展示模式」：短效臨時員工帳號。只在 SHOP_SANDBOX_MODE=true 且 SANDBOX_MFA_DEMO_ENABLED=true 時可建立。
 * <ul>
 * <li>帳號與隨機密碼只在建立回應出現一次；伺服器只存 bcrypt。</li>
 * <li>專用角色「MFA 展示」只有工作首頁與唯讀報表，沒有任何治理、商品、訂單、金流權限；角色在沙盒第一次建立帳號時才建立，正式 DB 不會出現。</li>
 * <li>登入完全走正式 MFA 程式路徑（綁定、TOTP、備援碼、保存確認），不另寫假的 MFA。</li>
 * <li>到期即拒絕登入與既有 session；排程清理刪除帳號、MFA、備援碼、MFA 事件與 Redis session。</li>
 * <li>鎖序：staff_access_guard → user 列 → MFA 列，與治理寫入、登入一致。</li>
 * </ul>
 */
@Service
public class SandboxDemoService {
    public static final String ROLE_NAME = "MFA 展示";
    private static final String ROLE_DESCRIPTION = "公開沙盒 MFA 展示臨時帳號（系統建立）";
    /** 展示角色只能有的權限（以 code 指定，不寫死 ID）：工作首頁、唯讀報表。 */
    static final List<String> ROLE_PERMISSIONS = List.of("workspace.read", "reports.sales", "reports.low-stock");
    private static final String ALNUM = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final Logger log = LoggerFactory.getLogger(SandboxDemoService.class);

    private final JdbcTemplate db; private final StringRedisTemplate redis; private final SandboxMode mode;
    private final BCryptPasswordEncoder passwords; private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();

    public SandboxDemoService(JdbcTemplate db, StringRedisTemplate redis, SandboxMode mode, BCryptPasswordEncoder passwords, PlatformTransactionManager tm) {
        this.db = db; this.redis = redis; this.mode = mode; this.passwords = passwords; this.tx = new TransactionTemplate(tm);
    }

    public record Created(String username, String password, OffsetDateTime expiresAt, int ttlMinutes) {}

    private static ResponseStatusException fail(int status, String message) { return new ResponseStatusException(HttpStatus.valueOf(status), message); }

    /** 建立一個臨時帳號。超過同時上限或全站每小時上限回 429（每 IP 限流由沙盒 proxy 負責）。 */
    public Created create() {
        if (!mode.demoEnabled()) throw fail(404, "找不到資源");
        // 全站每小時上限：先原子計數（失敗的嘗試也算，避免反覆觸發建立）
        String hourKey = "shop:sandbox:mfa-demo:created:" + (System.currentTimeMillis() / 3_600_000L);
        Long n = redis.opsForValue().increment(hourKey);
        if (n != null && n == 1) redis.expire(hourKey, Duration.ofHours(2));
        if (n == null || n > mode.demoCreatePerHour()) throw fail(429, "MFA 展示帳號本小時建立數量已達上限，請稍後再試");
        String password = randomText(PASSWORD_ALPHABET, 20);
        String hash = passwords.encode(password);
        return tx.execute(st -> {
            db.queryForObject("SELECT id FROM staff_access_guard WHERE id=1 FOR UPDATE", Integer.class);
            Integer active = db.queryForObject("SELECT COUNT(*) FROM sandbox_demo_account WHERE expires_at>CURRENT_TIMESTAMP(6)", Integer.class);
            if (active != null && active >= mode.demoMaxActive()) throw fail(429, "目前同時存在的 MFA 展示帳號已達上限，請稍後再試");
            int role = ensureRole();
            String username = null;
            for (int i = 0; i < 5 && username == null; i++) {
                String candidate = SandboxMode.DEMO_PREFIX + randomText(ALNUM, 8);
                if (db.queryForObject("SELECT COUNT(*) FROM user WHERE username=?", Integer.class, candidate) == 0) username = candidate;
            }
            if (username == null) throw fail(503, "暫時無法建立展示帳號，請稍後再試");
            db.update("INSERT INTO user(username,password,first_name,last_name,role,is_stop,created_time,modified_time) VALUES(?,?,?,'','S','F',NOW(),NOW())",
                    username, hash, "MFA 展示");
            int id = db.queryForObject("SELECT id FROM user WHERE username=?", Integer.class, username);
            db.update("INSERT INTO user_role(user_id,role_id) VALUES(?,?)", id, role);
            db.update("INSERT INTO sandbox_demo_account(user_id,expires_at) VALUES(?,CURRENT_TIMESTAMP(6)+INTERVAL ? MINUTE)", id, mode.demoTtlMinutes());
            LocalDateTime expires = db.queryForObject("SELECT expires_at FROM sandbox_demo_account WHERE user_id=?", LocalDateTime.class, id);
            return new Created(username, password, expires.atOffset(ZoneOffset.ofHours(8)), mode.demoTtlMinutes());
        });
    }

    /** 專用角色：不存在才建立（呼叫端已持有 staff_access_guard 鎖）；權限每次核對成只剩白名單。 */
    private int ensureRole() {
        var ids = db.queryForList("SELECT id FROM role WHERE name=? AND description=? ORDER BY id LIMIT 1", Integer.class, ROLE_NAME, ROLE_DESCRIPTION);
        int role;
        if (ids.isEmpty()) {
            role = db.queryForObject("SELECT COALESCE(MAX(id),0)+1 FROM role", Integer.class);
            db.update("INSERT INTO role(id,name,description,enabled,version) VALUES(?,?,?,TRUE,0)", role, ROLE_NAME, ROLE_DESCRIPTION);
        } else role = ids.get(0);
        db.update("UPDATE role SET enabled=TRUE WHERE id=?", role);
        String in = String.join(",", ROLE_PERMISSIONS.stream().map(c -> "?").toList());
        db.update("DELETE FROM role_permission WHERE role_id=? AND permission_id NOT IN (SELECT id FROM permissions_backend WHERE code IN (" + in + "))",
                concat(role, ROLE_PERMISSIONS));
        db.update("INSERT IGNORE INTO role_permission(role_id,permission_id) SELECT ?,id FROM permissions_backend WHERE code IN (" + in + ")",
                concat(role, ROLE_PERMISSIONS));
        return role;
    }
    private static Object[] concat(Object first, List<String> rest) {
        var out = new Object[rest.size() + 1]; out[0] = first;
        for (int i = 0; i < rest.size(); i++) out[i + 1] = rest.get(i);
        return out;
    }

    /**
     * 是否為 MFA 展示臨時帳號。生命週期限制「不」依賴目前的沙盒設定：即使之後關閉沙盒模式，
     * 已建立的臨時帳號仍是臨時帳號（見 {@link #blocked}），不會變成一般員工。
     */
    public boolean isDemoAccount(int user) {
        return db.queryForObject("SELECT COUNT(*) FROM sandbox_demo_account WHERE user_id=?", Integer.class, user) > 0;
    }
    /** 臨時帳號到期時間；非臨時帳號為 empty。 */
    public Optional<OffsetDateTime> expiresAt(int user) {
        var rows = db.queryForList("SELECT expires_at FROM sandbox_demo_account WHERE user_id=?", LocalDateTime.class, user);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0).atOffset(ZoneOffset.ofHours(8)));
    }
    /**
     * 臨時帳號不能使用：已到期（以 DB 時間判斷），或目前沒有開啟 MFA 展示模式（關閉沙盒後既有臨時帳號全部失效）。
     * 拒絕密碼登入、MFA 完成、既有 session 與 refresh。非臨時帳號一律 false。
     */
    public boolean blocked(int user) {
        var rows = db.queryForList("SELECT expires_at<=CURRENT_TIMESTAMP(6) AS expired FROM sandbox_demo_account WHERE user_id=?", user);
        if (rows.isEmpty()) return false;
        Object e = rows.get(0).get("expired");
        boolean expired = e instanceof Boolean b ? b : e instanceof Number n && n.intValue() != 0;
        return expired || !mode.demoEnabled();
    }
    public long activeCount() {
        if (!mode.enabled()) return 0;
        Long n = db.queryForObject("SELECT COUNT(*) FROM sandbox_demo_account WHERE expires_at>CURRENT_TIMESTAMP(6)", Long.class);
        return n == null ? 0 : n;
    }
    public SandboxMode.AccountKind kind(int user, String username) {
        if (mode.isExempt(username)) return SandboxMode.AccountKind.SANDBOX_SHARED;
        if (isDemoAccount(user)) return SandboxMode.AccountKind.SANDBOX_MFA_DEMO;   // 與沙盒設定無關
        return SandboxMode.AccountKind.STAFF;
    }

    /** 每 5 分鐘清理到期臨時帳號（與沙盒設定無關；正式環境這張表是空的）。每個帳號一筆短交易。 */
    @Scheduled(fixedDelayString = "${shop.sandbox.cleanup-delay-ms:300000}", initialDelayString = "${shop.sandbox.cleanup-delay-ms:300000}")
    public void scheduledCleanup() { cleanupExpired(); }

    public int cleanupExpired() {
        int removed = 0;
        for (Integer id : db.queryForList("SELECT user_id FROM sandbox_demo_account WHERE expires_at<=CURRENT_TIMESTAMP(6) ORDER BY user_id LIMIT 200", Integer.class)) {
            try {
                Boolean done = tx.execute(st -> {
                    db.queryForObject("SELECT id FROM staff_access_guard WHERE id=1 FOR UPDATE", Integer.class);
                    if (db.queryForList("SELECT id FROM user WHERE id=? FOR UPDATE", Integer.class, id).isEmpty()) return false;
                    if (db.queryForObject("SELECT COUNT(*) FROM sandbox_demo_account WHERE user_id=? AND expires_at<=CURRENT_TIMESTAMP(6)", Integer.class, id) == 0) return false;
                    db.queryForList("SELECT user_id FROM staff_mfa WHERE user_id=? FOR UPDATE", Integer.class, id);
                    db.update("DELETE FROM staff_mfa_backup_code WHERE user_id=?", id);
                    db.update("DELETE FROM staff_mfa WHERE user_id=?", id);
                    db.update("DELETE FROM staff_mfa_event WHERE user_id=?", id);
                    db.update("DELETE FROM user_role WHERE user_id=?", id);
                    db.update("DELETE FROM sandbox_demo_account WHERE user_id=?", id);
                    db.update("DELETE FROM user WHERE id=?", id);
                    return true;
                });
                if (Boolean.TRUE.equals(done)) { purgeRedis(id); removed++; }
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // 帳號被其他業務資料參照（例如客服單指派）：保留 user 列作為停用墓碑，不刪業務或稽核歷史；
                // 清除憑證、MFA、角色與 session，並移除臨時帳號紀錄，避免每次排程卡在同一筆
                try { if (Boolean.TRUE.equals(tombstone(id))) { purgeRedis(id); removed++; } }
                catch (RuntimeException t) { log.warn("停用到期 MFA 展示帳號 {} 失敗，下次重試：{}", id, t.getMessage()); }
            } catch (RuntimeException e) {
                log.warn("清理到期 MFA 展示帳號 {} 失敗，下次重試：{}", id, e.getMessage());
            }
        }
        return removed;
    }
    private Boolean tombstone(int id) {
        String unusable = passwords.encode(randomText(PASSWORD_ALPHABET, 32));   // 沒有人知道的密碼
        return tx.execute(st -> {
            db.queryForObject("SELECT id FROM staff_access_guard WHERE id=1 FOR UPDATE", Integer.class);
            if (db.queryForList("SELECT id FROM user WHERE id=? FOR UPDATE", Integer.class, id).isEmpty()) return false;
            if (db.queryForObject("SELECT COUNT(*) FROM sandbox_demo_account WHERE user_id=? AND expires_at<=CURRENT_TIMESTAMP(6)", Integer.class, id) == 0) return false;
            db.queryForList("SELECT user_id FROM staff_mfa WHERE user_id=? FOR UPDATE", Integer.class, id);
            db.update("DELETE FROM staff_mfa_backup_code WHERE user_id=?", id);
            db.update("DELETE FROM staff_mfa WHERE user_id=?", id);
            db.update("DELETE FROM staff_mfa_event WHERE user_id=?", id);
            db.update("DELETE FROM user_role WHERE user_id=?", id);
            db.update("DELETE FROM sandbox_demo_account WHERE user_id=?", id);
            db.update("UPDATE user SET is_stop='T',password=?,auth_version=auth_version+1,modified_time=NOW() WHERE id=?", unusable, id);
            return true;
        });
    }
    private void purgeRedis(int id) {
        var keys = redis.keys("shop:session:" + id + ":*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        redis.delete("shop:mfa:fail:" + id);
    }

    private String randomText(String alphabet, int length) {
        var b = new StringBuilder(length);
        for (int i = 0; i < length; i++) b.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return b.toString();
    }
}
