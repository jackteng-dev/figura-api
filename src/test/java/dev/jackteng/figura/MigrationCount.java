package dev.jackteng.figura;

import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Number of versioned demo migrations on the classpath; tests assert all of them applied successfully. */
final class MigrationCount {
    private MigrationCount() {}
    static int files() {
        try { return new PathMatchingResourcePatternResolver().getResources("classpath*:db/demo-migration/V*__*.sql").length; }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
}
