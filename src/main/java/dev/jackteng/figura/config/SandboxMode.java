package dev.jackteng.figura.config;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 公開沙盒模式（只由伺服器端環境變數開啟；預設全部關閉）。
 * <ul>
 * <li>{@code SHOP_SANDBOX_MODE=true} 是所有沙盒功能的總開關。總開關沒開、卻設定了任何沙盒變數 → 啟動失敗。</li>
 * <li>沙盒模式與正式金流旗標（{@code shop.payments.ecpay.live=true}）同時出現 → 啟動失敗，防止正式環境誤開。</li>
 * <li>沒有任何請求參數、header、cookie 可以改變這些設定；它們只在啟動時從環境讀取一次。</li>
 * </ul>
 */
@Component
public class SandboxMode {
    public static final String DEMO_PREFIX = "mfa-demo-";
    public enum AccountKind { STAFF, SANDBOX_SHARED, SANDBOX_MFA_DEMO }
    private static final int DEFAULT_TTL = 60, DEFAULT_MAX_ACTIVE = 50, DEFAULT_PER_HOUR = 200;

    private final boolean enabled, demoEnabled;
    private final Set<String> exempt;
    private final int demoTtlMinutes, demoMaxActive, demoCreatePerHour;

    public SandboxMode(@Value("${SHOP_SANDBOX_MODE:}") String mode,
                       @Value("${SANDBOX_MFA_EXEMPT_USERNAMES:}") String exemptUsernames,
                       @Value("${SANDBOX_MFA_DEMO_ENABLED:}") String demo,
                       @Value("${SANDBOX_MFA_DEMO_TTL_MINUTES:}") String ttl,
                       @Value("${SANDBOX_MFA_DEMO_MAX_ACTIVE:}") String maxActive,
                       @Value("${SANDBOX_MFA_DEMO_CREATE_PER_HOUR:}") String perHour,
                       @Value("${shop.payments.ecpay.live:false}") String ecpayLive) {
        this.enabled = flag("SHOP_SANDBOX_MODE", mode);
        this.demoEnabled = flag("SANDBOX_MFA_DEMO_ENABLED", demo);
        this.demoTtlMinutes = number("SANDBOX_MFA_DEMO_TTL_MINUTES", ttl, DEFAULT_TTL, 5, 1440);
        this.demoMaxActive = number("SANDBOX_MFA_DEMO_MAX_ACTIVE", maxActive, DEFAULT_MAX_ACTIVE, 1, 500);
        this.demoCreatePerHour = number("SANDBOX_MFA_DEMO_CREATE_PER_HOUR", perHour, DEFAULT_PER_HOUR, 1, 5000);
        // 任一沙盒變數不是預設值，但總開關沒開 → 拒絕啟動（正式環境誤設也不會靜默生效或被忽略）
        boolean anySandboxSetting = !blank(exemptUsernames) || demoEnabled || demoTtlMinutes != DEFAULT_TTL
                || demoMaxActive != DEFAULT_MAX_ACTIVE || demoCreatePerHour != DEFAULT_PER_HOUR;
        if (!enabled && anySandboxSetting)
            throw new IllegalStateException("設定了 SANDBOX_* 沙盒變數，但 SHOP_SANDBOX_MODE 不是 true：拒絕啟動（正式環境不得出現沙盒變數）");
        if (enabled && flag("shop.payments.ecpay.live", ecpayLive))
            throw new IllegalStateException("SHOP_SANDBOX_MODE=true 不可與正式金流（shop.payments.ecpay.live=true）同時啟用：拒絕啟動");
        var names = new LinkedHashSet<String>();
        if (!blank(exemptUsernames)) for (String raw : exemptUsernames.split(",")) {
            String name = raw.strip();   // 只去掉清單分隔處的空白；比對登入帳號時完全比對、大小寫敏感
            if (name.isEmpty()) continue;
            if (!name.matches("[A-Za-z0-9_.-]{1,64}") || name.startsWith(DEMO_PREFIX))
                throw new IllegalStateException("SANDBOX_MFA_EXEMPT_USERNAMES 含無效帳號名稱");
            names.add(name);
        }
        this.exempt = Set.copyOf(names);
        if (enabled) LoggerFactory.getLogger(SandboxMode.class).warn("公開沙盒模式已啟用（SHOP_SANDBOX_MODE=true）：快速展示帳號 {} 個（免 MFA）、MFA 展示模式 {}。正式環境絕不可設定此模式。",
                exempt.size(), demoEnabled ? "開啟（TTL " + demoTtlMinutes + " 分、上限 " + demoMaxActive + "、每小時 " + demoCreatePerHour + "）" : "關閉");
    }

    /** 只接受字面值 true／false（或未設定）；其他值一律拒絕啟動，避免 "yes"、"1" 之類的誤判。 */
    private static boolean flag(String name, String value) {
        if (blank(value) || "false".equals(value.strip())) return false;
        if ("true".equals(value.strip())) return true;
        throw new IllegalStateException(name + " 只能是 true 或 false");
    }
    private static int number(String name, String value, int fallback, int min, int max) {
        if (blank(value)) return fallback;
        try {
            int n = Integer.parseInt(value.strip());
            if (n < min || n > max) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) { throw new IllegalStateException(name + " 必須是 " + min + "–" + max + " 的整數"); }
    }
    private static boolean blank(String v) { return v == null || v.isBlank(); }

    public boolean enabled() { return enabled; }
    public boolean demoEnabled() { return enabled && demoEnabled; }
    public boolean quickDemoEnabled() { return enabled && !exempt.isEmpty(); }
    /** 共用展示帳號：只在沙盒模式、且帳號名稱與設定完全相同（大小寫、空白都要一致）。 */
    public boolean isExempt(String username) { return enabled && username != null && exempt.contains(username); }
    public int exemptCount() { return enabled ? exempt.size() : 0; }
    public int demoTtlMinutes() { return demoTtlMinutes; }
    public int demoMaxActive() { return demoMaxActive; }
    public int demoCreatePerHour() { return demoCreatePerHour; }
    @Override public String toString() { return "SandboxMode[enabled=" + enabled + ",exempt=" + exempt.size() + ",demo=" + demoEnabled + "]"; }
}
