package dev.jackteng.figura;

import dev.jackteng.figura.config.SandboxSeedCatalog;
import dev.jackteng.figura.config.SandboxSeedFixture;
import dev.jackteng.figura.config.SandboxSeeder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 沙盒初始化入口（spec 2.4／2.5-9）：95 件 × 5 張圖、類型與排序正確、AI 標示保留、現貨與預購、8 筆示範訂單；重跑冪等；
 * 驗證失敗不寫入；系統帳號執行完即停用。需要 MySQL 與 SeaweedFS（CI 的隔離 fixture）。
 */
@SpringBootTest(properties = {"shop.orders.expiry-delay-ms=600000", "SHOP_SANDBOX_MODE=true", "SANDBOX_MFA_EXEMPT_USERNAMES=" + SandboxIntegrationTest.SHARED,
        "SANDBOX_MFA_DEMO_ENABLED=true", "SANDBOX_MFA_DEMO_MAX_ACTIVE=3", "SANDBOX_MFA_DEMO_TTL_MINUTES=30", "shop.sandbox.cleanup-delay-ms=3600000",
        // 與 SandboxIntegrationTest 完全相同，共用同一個 Spring context（CI MySQL 連線數有限）
        "MOCK_PAYMENTS_ENABLED=true", "MOCK_FULFILLMENT_ENABLED=true", "MOCK_RETURNS_ENABLED=true", "MOCK_REFUNDS_ENABLED=true"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "SHOP_MYSQL_INTEGRATION", matches = "true")
@EnabledIfEnvironmentVariable(named = "SHOP_IMAGES_INTEGRATION", matches = "true")
class SandboxSeederIntegrationTest extends ShopIntegrationSupport {
    @Autowired SandboxSeeder seeder;
    @TempDir Path dir;

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private static final String CODES = "s.code LIKE 'FIGURA-AI-%'";

    @Test void seedsTheWholeCatalogAndIsIdempotent(@TempDir Path broken) throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        var doc = SandboxSeedFixture.catalog(dir, run);
        SandboxSeedFixture.write(dir, doc);

        // 驗證失敗（缺一張圖）→ exit 4，且沒有任何寫入
        copy(dir, broken);
        @SuppressWarnings("unchecked") var firstImages = (List<Map<String, Object>>) ((List<Map<String, Object>>) doc.get("items")).get(0).get("images");
        Files.delete(broken.resolve((String) firstImages.get(3).get("file")));
        int productsBefore = count("SELECT COUNT(*) FROM catalog_product"), eventsBefore = count("SELECT COUNT(*) FROM catalog_master_event");
        assertEquals(4, seeder.execute(new DefaultApplicationArguments("--sandbox.seed.dir=" + broken)));
        assertEquals(productsBefore, count("SELECT COUNT(*) FROM catalog_product")); assertEquals(eventsBefore, count("SELECT COUNT(*) FROM catalog_master_event"));

        // 第一次初始化
        var summary = seeder.seed(dir);
        assertEquals("SEEDED", summary.result());
        assertEquals(95, summary.products()); assertEquals(95, summary.published()); assertEquals(64, summary.preorders()); assertEquals(8, summary.orders());
        assertEquals(Map.of("MAIN", 95, "FACE", 95, "DETAIL", 95, "SCENE", 95, "REAR", 95), summary.images());

        // 每件 5 張圖，類型與排序正確，主圖是 MAIN
        for (var row : jdbc.queryForList("SELECT s.product_id FROM catalog_sku s WHERE " + CODES)) {
            String product = (String) row.get("product_id");
            var types = jdbc.queryForList("SELECT image_type FROM product_image_gallery WHERE product_id=? ORDER BY position", String.class, product);
            assertEquals(SandboxSeedCatalog.IMAGE_TYPES, types);
            assertEquals(List.of(0, 1, 2, 3, 4), jdbc.queryForList("SELECT position FROM product_image_gallery WHERE product_id=? ORDER BY position", Integer.class, product));
            assertEquals("MAIN", jdbc.queryForObject("SELECT g.image_type FROM product_image_cover c JOIN product_image_gallery g ON g.product_id=c.product_id AND g.image_id=c.image_id WHERE c.product_id=?", String.class, product));
        }
        // AI 標示保留
        assertEquals(95, count("SELECT COUNT(DISTINCT p.id) FROM catalog_product p JOIN catalog_sku s ON s.product_id=p.id WHERE " + CODES + " AND p.content_origin='AI_CONCEPT' AND p.description LIKE ?", SandboxSeedCatalog.AI_MARKER + "%"));
        // 現貨、售完、預購
        assertEquals(30, count("SELECT COUNT(*) FROM sku_inventory i JOIN catalog_sku s ON s.id=i.sku_id WHERE " + CODES + " AND i.quantity>0"));
        assertEquals(0, count("SELECT COUNT(*) FROM sku_inventory i JOIN catalog_sku s ON s.id=i.sku_id WHERE s.code='FIGURA-AI-095' AND i.quantity>0"));
        // 示範訂單狀態
        var states = jdbc.queryForList("SELECT o.status,o.fulfillment_status,(SELECT COUNT(*) FROM mock_order_return r WHERE r.order_id=o.id) ret FROM customer_order o JOIN customer c ON c.id=o.customer_id WHERE c.email LIKE ?", "sandbox-seed-%-" + run + "@example.test");
        assertEquals(8, states.size());
        assertEquals(1, states.stream().filter(r -> "PENDING_PAYMENT".equals(r.get("status"))).count());
        assertEquals(2, states.stream().filter(r -> "DELIVERED".equals(r.get("fulfillment_status")) && ((Number) r.get("ret")).intValue() == 0).count());
        assertEquals(1, states.stream().filter(r -> ((Number) r.get("ret")).intValue() == 1).count());
        // 稽核 actor 是系統帳號；執行完即停用
        assertTrue(count("SELECT COUNT(*) FROM inventory_adjustment a JOIN catalog_sku s ON s.id=a.sku_id WHERE " + CODES + " AND a.actor_username=?", SandboxSeeder.ACTOR) >= 30);
        assertEquals("T", jdbc.queryForObject("SELECT is_stop FROM user WHERE username=?", String.class, SandboxSeeder.ACTOR));

        // 重跑：結果相同、沒有任何重複建立
        int gallery = count("SELECT COUNT(*) FROM product_image_gallery"), adjustments = count("SELECT COUNT(*) FROM inventory_adjustment"),
                batches = count("SELECT COUNT(*) FROM preorder_batch"), orders = count("SELECT COUNT(*) FROM customer_order"), customers = count("SELECT COUNT(*) FROM customer"),
                masters = count("SELECT COUNT(*) FROM catalog_master_event");
        assertEquals(0, seeder.execute(new DefaultApplicationArguments("--sandbox.seed.dir=" + dir)));
        assertEquals(gallery, count("SELECT COUNT(*) FROM product_image_gallery")); assertEquals(adjustments, count("SELECT COUNT(*) FROM inventory_adjustment"));
        assertEquals(batches, count("SELECT COUNT(*) FROM preorder_batch")); assertEquals(orders, count("SELECT COUNT(*) FROM customer_order"));
        assertEquals(customers, count("SELECT COUNT(*) FROM customer")); assertEquals(masters, count("SELECT COUNT(*) FROM catalog_master_event"));
        assertEquals("T", jdbc.queryForObject("SELECT is_stop FROM user WHERE username=?", String.class, SandboxSeeder.ACTOR));
    }

    /**
     * 共用測試 DB：結束後下架並封存本測試建立的商品、關閉其預購批次，並把 SKU code 改成不再佔用的值，
     * 避免影響其他測試的公開商品數量，也讓下次執行重新建立。只動本測試自己建立的 fixture，不刪任何稽核。
     */
    @org.junit.jupiter.api.AfterEach void releaseFixtures() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("UPDATE preorder_batch b JOIN catalog_sku s ON s.id=b.sku_id SET b.status='CLOSED',b.closed_at=CURRENT_TIMESTAMP(6),b.version=b.version+1 WHERE b.status='OPEN' AND s.code LIKE 'FIGURA-AI-%'");
        jdbc.update("UPDATE catalog_product p JOIN catalog_sku s ON s.product_id=p.id SET p.status='ARCHIVED',p.version=p.version+1 WHERE s.code LIKE 'FIGURA-AI-%'");
        jdbc.update("UPDATE catalog_sku SET code=CONCAT('SEEDTEST-',?,'-',SUBSTRING(code,12)) WHERE code LIKE 'FIGURA-AI-%'", tag);
    }

    @Test void argumentsMustNameExactlyOneDirectory() {
        assertEquals(2, seeder.execute(new DefaultApplicationArguments("--sandbox.seed.dir=")));
        assertEquals(2, seeder.execute(new DefaultApplicationArguments("--sandbox.seed.dir=/a", "--sandbox.seed.dir=/b")));
        assertEquals(4, seeder.execute(new DefaultApplicationArguments("--sandbox.seed.dir=" + dir.resolve("missing"))));
    }

    private static void copy(Path from, Path to) throws Exception {
        try (var walk = Files.walk(from)) {
            for (Path p : walk.toList()) { Path t = to.resolve(from.relativize(p).toString()); if (Files.isDirectory(p)) Files.createDirectories(t); else Files.copy(p, t); }
        }
    }
}
