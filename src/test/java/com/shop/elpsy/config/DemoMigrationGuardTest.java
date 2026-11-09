package com.shop.elpsy.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DemoMigrationGuardTest {
    @Test void wrongDatabaseRefusesBeforeMigration() throws Exception { check("original_shop", false); }
    @Test void exactDemoDatabaseAllowsMigration() throws Exception { check("elpsy_shop_demo", true); }
    @Test void metadataFailureDoesNotMigrate() throws Exception {
        var flyway=mock(Flyway.class);var config=mock(Configuration.class);var source=mock(DataSource.class);
        when(flyway.getConfiguration()).thenReturn(config);when(config.getDataSource()).thenReturn(source);when(source.getConnection()).thenThrow(new java.sql.SQLException());
        assertThrows(IllegalStateException.class,()->new DemoMigrationGuard().guardedDemoMigration().migrate(flyway));verify(flyway,never()).migrate();
    }
    private void check(String catalog, boolean allowed) throws Exception {
        var flyway=mock(Flyway.class);var config=mock(Configuration.class);var source=mock(DataSource.class);var connection=mock(Connection.class);
        when(flyway.getConfiguration()).thenReturn(config);when(config.getDataSource()).thenReturn(source);when(source.getConnection()).thenReturn(connection);when(connection.getCatalog()).thenReturn(catalog);
        if(allowed){new DemoMigrationGuard().guardedDemoMigration().migrate(flyway);verify(flyway).migrate();}
        else {assertThrows(IllegalStateException.class,()->new DemoMigrationGuard().guardedDemoMigration().migrate(flyway));verify(flyway,never()).migrate();}
        verify(connection).close();
    }
}
