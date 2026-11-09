package com.shop.elpsy.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("demo")
public class DemoMigrationGuard {
    @Bean FlywayMigrationStrategy guardedDemoMigration() {
        return flyway -> {
            try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
                if (!"elpsy_shop_demo".equals(connection.getCatalog()))
                    throw new IllegalStateException("demo migration 僅允許隔離 elpsy_shop_demo");
            } catch (java.sql.SQLException e) { throw new IllegalStateException("無法核對 demo DB", e); }
            flyway.migrate();
        };
    }
}
