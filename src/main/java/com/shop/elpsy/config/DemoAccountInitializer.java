package com.shop.elpsy.config;

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
    public DemoAccountInitializer(JdbcTemplate jdbc, BCryptPasswordEncoder encoder,
                                  @Value("${DEMO_ADMIN_PASSWORD:}") String adminPassword,
                                  @Value("${DEMO_STAFF_PASSWORD:}") String staffPassword) {
        this.jdbc = jdbc; this.encoder = encoder; this.adminPassword = adminPassword; this.staffPassword = staffPassword;
    }
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!"elpsy_shop_demo".equals(jdbc.queryForObject("SELECT DATABASE()", String.class)))
            throw new IllegalStateException("demo 僅可使用獨立 elpsy_shop_demo 資料庫");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM user", Integer.class) != 0) return;
        if (adminPassword.length() < 12 || staffPassword.length() < 12 || adminPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
                || staffPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72 || adminPassword.equals(staffPassword))
            throw new IllegalStateException("首次 demo 需提供不同且至少 12 字元的私有管理員／員工密碼");
        create("shop_admin", adminPassword, 1);
        create("shop_staff", staffPassword, 2);
    }
    private void create(String name, String password, int role) {
        jdbc.update("INSERT INTO user(username,password,first_name,last_name,role,is_stop,created_time,modified_time) VALUES(?,?,?,'','S','F',NOW(),NOW())",
                name, encoder.encode(password), name);
        Integer id = jdbc.queryForObject("SELECT id FROM user WHERE username=?", Integer.class, name);
        jdbc.update("INSERT INTO user_role(user_id,role_id) VALUES(?,?)", id, role);
    }
}
