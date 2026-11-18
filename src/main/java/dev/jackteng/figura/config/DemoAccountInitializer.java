package dev.jackteng.figura.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("demo")
public class DemoAccountInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder;
    private final String adminPassword;
    private final String staffPassword;
    private final String customerPassword;
    /** 沙盒示範會員：依新版會員規則（手機＋Email＋密碼）建立。README 與 App Release 說明使用同一組資料。 */
    public static final String DEMO_CUSTOMER_EMAIL = "demo-customer@example.com";
    public static final String DEMO_CUSTOMER_PHONE = "0900000000";
    public DemoAccountInitializer(JdbcTemplate jdbc, BCryptPasswordEncoder encoder,
                                  @Value("${DEMO_ADMIN_PASSWORD:}") String adminPassword,
                                  @Value("${DEMO_STAFF_PASSWORD:}") String staffPassword,
                                  @Value("${DEMO_CUSTOMER_PASSWORD:}") String customerPassword) {
        this.jdbc = jdbc; this.encoder = encoder; this.adminPassword = adminPassword; this.staffPassword = staffPassword;
        this.customerPassword = customerPassword;
    }
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!DemoMigrationGuard.DEMO_DATABASES.contains(jdbc.queryForObject("SELECT DATABASE()", String.class)))
            throw new IllegalStateException("demo 僅可使用獨立 figura_shop_demo 資料庫");
        createDemoCustomer();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM user", Integer.class) != 0) return;
        if (adminPassword.length() < 12 || staffPassword.length() < 12 || adminPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
                || staffPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72 || adminPassword.equals(staffPassword))
            throw new IllegalStateException("首次 demo 需提供不同且至少 12 字元的私有管理員／員工密碼");
        create("shop_admin", adminPassword, 1);
        create("shop_staff", staffPassword, 2);
    }
    /**
     * 有提供 DEMO_CUSTOMER_PASSWORD（私有環境）才建立；只建立一次，已存在就不動（不重設既有帳號的密碼）。
     * 內部 username／display_name 為系統產生值，不能用來登入。Email 標記為已驗證（示範帳號不需收信）。
     */
    private void createDemoCustomer() {
        if (customerPassword.isBlank()) return;
        if (customerPassword.length() < 12 || customerPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new IllegalStateException("示範會員密碼需至少 12 字元、不超過 72 bytes");
        Integer byEmail = jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE email=?", Integer.class, DEMO_CUSTOMER_EMAIL);
        if (byEmail != 0) return;
        Integer byPhone = jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE phone=?", Integer.class, DEMO_CUSTOMER_PHONE);
        if (byPhone != 0) throw new IllegalStateException("示範會員手機已被其他帳號使用，請先核對資料");
        String id = java.util.UUID.randomUUID().toString();
        jdbc.update("INSERT INTO customer(id,username,password_hash,display_name,email,phone,email_verified_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP(6))",
                id, "m_" + id.replace("-", "").substring(0, 24), encoder.encode(customerPassword), "示範會員", DEMO_CUSTOMER_EMAIL, DEMO_CUSTOMER_PHONE);
    }
    private void create(String name, String password, int role) {
        jdbc.update("INSERT INTO user(username,password,first_name,last_name,role,is_stop,created_time,modified_time) VALUES(?,?,?,'','S','F',NOW(),NOW())",
                name, encoder.encode(password), name);
        Integer id = jdbc.queryForObject("SELECT id FROM user WHERE username=?", Integer.class, name);
        jdbc.update("INSERT INTO user_role(user_id,role_id) VALUES(?,?)", id, role);
    }
}
