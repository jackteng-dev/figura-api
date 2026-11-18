package dev.jackteng.figura.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 沙盒示範會員（demo profile）：依新版會員規則（手機＋Email＋密碼）建立。README 與 App Release 說明使用同一組資料。
 * <ul>
 *   <li>只有私有環境提供 {@code DEMO_CUSTOMER_PASSWORD} 才啟用；沒設定就完全不動。</li>
 *   <li>缺少就建立；已存在只核對手機、啟用、Email 已驗證與密碼，<b>不修改資料、不重設密碼</b>（AGENTS：不能重設已有用戶）。</li>
 *   <li>啟動時檢查一次；資料結構不符（手機、啟用、驗證）停止啟動，密碼不符只記錄錯誤（避免公開沙盒整站起不來）。</li>
 *   <li>選用 {@code DEMO_CUSTOMER_SELF_HEAL=true}：每分鐘檢查一次，資料被重置清空時自動補建。
 *       每日重置若在 API 執行中還原「含示範會員」的快照，可能和補建撞到唯一鍵，所以建議重置流程先停 API。</li>
 * </ul>
 */
@Component
@Profile("demo")
public class DemoCustomerSeeder {
    public static final String EMAIL = "demo-customer@example.com";
    public static final String PHONE = "0900000000";
    public enum Status { DISABLED, CREATED, OK, PHONE_TAKEN, MISMATCH, PASSWORD_MISMATCH }

    private static final Logger log = LoggerFactory.getLogger(DemoCustomerSeeder.class);
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder;
    private final String password;
    private final boolean selfHeal;
    private volatile Status lastReported;

    public DemoCustomerSeeder(JdbcTemplate jdbc, BCryptPasswordEncoder encoder,
                              @Value("${DEMO_CUSTOMER_PASSWORD:}") String password,
                              @Value("${DEMO_CUSTOMER_SELF_HEAL:false}") boolean selfHeal) {
        this.jdbc = jdbc; this.encoder = encoder; this.password = password; this.selfHeal = selfHeal;
    }

    public boolean enabled() { return !password.isBlank(); }

    /** 缺少就建立，已存在只核對。回傳目前狀態（設定錯誤才丟例外），由呼叫端決定是否中止。 */
    public Status ensure() {
        if (!enabled()) return Status.DISABLED;
        if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalStateException("示範會員密碼需至少 12 字元、不超過 72 bytes");
        if (!DemoMigrationGuard.DEMO_DATABASES.contains(jdbc.queryForObject("SELECT DATABASE()", String.class)))
            throw new IllegalStateException("demo 僅可使用獨立 figura_shop_demo 資料庫");
        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT phone, enabled, email_verified_at IS NOT NULL verified, password_hash FROM customer WHERE email=?", EMAIL);
        if (rows.isEmpty()) {
            Integer byPhone = jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE phone=?", Integer.class, PHONE);
            if (byPhone != 0) return Status.PHONE_TAKEN;
            String id = UUID.randomUUID().toString();
            try {
                jdbc.update("INSERT INTO customer(id,username,password_hash,display_name,email,phone,email_verified_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP(6))",
                        id, "m_" + id.replace("-", "").substring(0, 24), encoder.encode(password), "示範會員", EMAIL, PHONE);
            } catch (DuplicateKeyException race) {
                return Status.MISMATCH; // 同時有別的程序寫入同 Email／手機：下次檢查再核對
            }
            return Status.CREATED;
        }
        var row = rows.get(0);
        boolean structure = PHONE.equals(row.get("phone")) && truthy(row.get("enabled")) && truthy(row.get("verified"));
        if (!structure) return Status.MISMATCH;
        return encoder.matches(password, String.valueOf(row.get("password_hash"))) ? Status.OK : Status.PASSWORD_MISMATCH;
    }

    /** 啟動時：結構問題停止啟動；密碼不符只記錄錯誤。 */
    public void ensureAtStartup() {
        Status s = ensure();
        switch (s) {
            case PHONE_TAKEN -> throw new IllegalStateException("示範會員手機已被其他帳號使用，請先核對資料");
            case MISMATCH -> throw new IllegalStateException("既有示範會員資料不符合新版規則（手機、啟用或 Email 驗證），請先人工核對");
            default -> report(s);
        }
    }

    /** 選用的定期檢查：重置清空資料後自動補建；任何錯誤只記錄，不影響服務。 */
    @Scheduled(fixedDelayString = "${shop.demo.customer-check-delay-ms:60000}", initialDelayString = "${shop.demo.customer-check-delay-ms:60000}")
    public void heal() {
        if (!selfHeal || !enabled()) return;
        try { report(ensure()); }
        catch (RuntimeException e) { log.error("示範會員定期檢查失敗：{}", e.getMessage()); }
    }

    private void report(Status s) {
        if (s == lastReported && s != Status.CREATED) return; // 狀態沒變就不重複記錄
        lastReported = s;
        switch (s) {
            case CREATED -> log.info("已建立沙盒示範會員 {}", EMAIL);
            case PASSWORD_MISMATCH -> log.error("沙盒示範會員密碼與 DEMO_CUSTOMER_PASSWORD 不一致，公開說明的帳密無法登入；依規則不自動重設，請由每日重置還原或人工處理");
            case MISMATCH, PHONE_TAKEN -> log.error("沙盒示範會員資料不符合新版規則（{}），請人工核對", s);
            default -> { }
        }
    }

    private static boolean truthy(Object v) {
        return v instanceof Boolean b ? b : v instanceof Number n && n.intValue() != 0;
    }
}
