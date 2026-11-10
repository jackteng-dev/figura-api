package dev.jackteng.figura.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("demo")
public class DemoMigrationGuard {
    /** Isolated demo databases. elpsy_shop_demo is the pre-rename name, accepted until the server DB is renamed. */
    public static final java.util.Set<String> DEMO_DATABASES = java.util.Set.of("figura_shop_demo", "elpsy_shop_demo");

    @Bean FlywayMigrationStrategy guardedDemoMigration() {
        return flyway -> {
            try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
                if (!DEMO_DATABASES.contains(connection.getCatalog()))
                    throw new IllegalStateException("demo migration 僅允許隔離 figura_shop_demo");
            } catch (java.sql.SQLException e) { throw new IllegalStateException("無法核對 demo DB", e); }
            flyway.migrate();
        };
    }
}
