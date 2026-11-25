package dev.jackteng.figura;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jackteng.figura.service.ProductAlertScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Commerce V2 Phase 3：收藏、商品提醒（狀態轉換才通知、不重複）、通知偏好、站內通知匣與 mock Email。 */
@SpringBootTest(properties = {"shop.orders.expiry-delay-ms=600000", "shop.notifications.mock-enabled=true", "MOCK_EMAIL_ENABLED=true"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "SHOP_MYSQL_INTEGRATION", matches = "true")
class EngagementIntegrationTest extends ShopIntegrationSupport {
    @Autowired ProductAlertScanner scanner;

    private List<String> ids(JsonNode n) { var out = new ArrayList<String>(); n.path("productIds").forEach(x -> out.add(x.asText())); return out; }

    @Test void favoritesAreOwnedIdempotentAndMergeSafely() throws Exception {
        var a = admin(); var c = customer(); var other = customer();
        var f = fixture(a, 3); var g = fixture(a, 0);
        assertEquals(List.of(f.product()), ids(data(ok(c, "PUT", "/customer/favorites/" + f.product(), null, 200))));
        assertEquals(List.of(f.product()), ids(data(ok(c, "PUT", "/customer/favorites/" + f.product(), null, 200))), "重複收藏冪等");
        var list = data(ok(c, "GET", "/customer/favorites", null, 200));
        assertEquals(1, list.path("total").asInt());
        assertEquals("IN_STOCK", list.path("items").get(0).path("product").path("availability").asText());
        assertEquals(0, data(ok(other, "GET", "/customer/favorites", null, 200)).path("total").asInt(), "不同顧客互不可見");
        // 合併：已存在＋新上架商品成功，不存在的商品略過；重送結果相同
        String missing = uuid();
        var merged = data(ok(c, "POST", "/customer/favorites/merge", Map.of("productIds", List.of(f.product(), g.product(), missing)), 200));
        assertEquals(2, merged.path("merged").size()); assertEquals(missing, merged.path("skipped").get(0).asText()); assertEquals(2, merged.path("total").asInt());
        assertEquals(2, data(ok(c, "POST", "/customer/favorites/merge", Map.of("productIds", List.of(f.product(), g.product())), 200)).path("total").asInt());
        // 下架商品不能新增收藏，已收藏的從清單隱藏
        jdbc.update("UPDATE catalog_product SET status='ARCHIVED' WHERE id=?", g.product());
        assertEquals(1, data(ok(c, "GET", "/customer/favorites", null, 200)).path("total").asInt());
        assertEquals(404, call(other, "PUT", "/customer/favorites/" + g.product(), null).getResponse().getStatus());
        assertEquals(List.of(g.product()), ids(data(ok(c, "DELETE", "/customer/favorites/" + f.product(), null, 200))));
        assertEquals(400, call(c, "PUT", "/customer/favorites/not-a-uuid", null).getResponse().getStatus());
        assertEquals(400, call(c, "POST", "/customer/favorites/merge", Map.of("productIds", List.of("x"))).getResponse().getStatus());
        int guest = call(guest(), "PUT", "/customer/favorites/" + f.product(), null).getResponse().getStatus();
        assertTrue(guest == 401 || guest == 403, "訪客不可寫入：" + guest);
    }

    @Test void alertsAndPreferencesValidateAndVersion() throws Exception {
        var a = admin(); var c = customer(); var f = fixture(a, 0);
        assertEquals(List.of("PREORDER_OPEN", "RESTOCK"), toList(data(ok(c, "PUT", "/customer/product-alerts/" + f.product(), Map.of("kinds", List.of("restock", "PREORDER_OPEN", "RESTOCK")), 200))));
        assertEquals(400, call(c, "PUT", "/customer/product-alerts/" + f.product(), Map.of("kinds", List.of("PRICE_DROP"))).getResponse().getStatus());
        var alerts = data(ok(c, "GET", "/customer/product-alerts", null, 200));
        assertEquals(f.product(), alerts.get(0).path("productId").asText()); assertEquals("SOLD_OUT", alerts.get(0).path("availability").asText());
        assertEquals(List.of(), toList(data(ok(c, "PUT", "/customer/product-alerts/" + f.product(), Map.of("kinds", List.of()), 200))));
        var pref = data(ok(c, "GET", "/customer/notification-preferences", null, 200));
        assertFalse(pref.path("emailEnabled").asBoolean()); assertTrue(pref.path("orderUpdates").asBoolean()); assertEquals(0, pref.path("version").asInt());
        var body = Map.of("emailEnabled", true, "orderUpdates", false, "productAlerts", true, "expectedVersion", 0);
        assertEquals(1, data(ok(c, "PUT", "/customer/notification-preferences", body, 200)).path("version").asInt());
        assertEquals(409, call(c, "PUT", "/customer/notification-preferences", body).getResponse().getStatus(), "舊版本不能覆寫");
    }

    @Test void scannerNotifiesOnlyOnTransitionsAndEmailsWhenAllowed() throws Exception {
        var a = admin(); var c = customer(); var f = fixture(a, 0);
        jdbc.update("UPDATE customer SET email_verified_at=CURRENT_TIMESTAMP(6) WHERE id=?", c.id());
        ok(c, "PUT", "/customer/notification-preferences", Map.of("emailEnabled", true, "orderUpdates", true, "productAlerts", true, "expectedVersion", 0), 200);
        ok(c, "PUT", "/customer/product-alerts/" + f.product(), Map.of("kinds", List.of("RESTOCK", "PREORDER_OPEN", "PREORDER_CLOSING")), 200);
        assertEquals(0, scanner.scanOne(f.product()), "第一次只建立基準");
        // 開放預購（截止在 48 小時內）→ 開放＋即將截止各一則
        batch(a, f, 5, 1500, true);
        assertEquals(2, scanner.scanOne(f.product()));
        assertEquals(0, scanner.scanOne(f.product()), "同一狀態不重複通知");
        // 補貨
        ok(a, "POST", "/admin/products/" + f.product() + "/skus/" + f.sku() + "/inventory/adjustments", Map.of("requestId", uuid(), "expectedVersion", 0, "delta", 2, "reason", "RESTOCK", "note", "補貨"), 201);
        assertEquals(1, scanner.scanOne(f.product()));
        assertEquals(0, scanner.scanOne(f.product()));
        var inbox = data(ok(c, "GET", "/customer/notifications", null, 200));
        assertEquals(3, inbox.path("total").asInt()); assertEquals(3, inbox.path("unread").asInt());
        var kinds = new HashSet<String>(); inbox.path("items").forEach(n -> { kinds.add(n.path("kind").asText()); assertEquals("PRODUCT", n.path("category").asText()); assertEquals(f.product(), n.path("productId").asText()); });
        assertEquals(Set.of("PRODUCT_RESTOCK", "PRODUCT_PREORDER_OPEN", "PRODUCT_PREORDER_CLOSING"), kinds);
        var restock = find(inbox, "PRODUCT_RESTOCK");
        assertEquals("商品補貨了", restock.path("title").asText()); assertTrue(restock.path("message").asText().contains("現貨"));
        // 其他顧客看不到、也不能標記
        var other = customer();
        assertEquals(0, data(ok(other, "GET", "/customer/notifications", null, 200)).path("total").asInt());
        assertEquals(0, data(ok(other, "POST", "/customer/notifications/read", Map.of("ids", List.of(restock.path("id").asText())), 200)).path("updated").asInt());
        // 派送成功 → 依偏好寫入 mock Email（不實際寄出）
        var box = data(ok(a, "GET", "/admin/notifications/" + restock.path("id").asText(), null, 200)).path("message");
        ok(a, "POST", "/admin/notifications/" + restock.path("id").asText() + "/mock-dispatch", Map.of("requestId", uuid(), "expectedVersion", box.path("version").asInt(), "note", "測試派送", "mode", "SUCCESS"), 200);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM customer_mock_email WHERE customer_id=? AND purpose='NOTIFY' AND link=?", Integer.class, c.id(), "/shop/products/" + f.product()));
        // 標記已讀
        var read = data(ok(c, "POST", "/customer/notifications/read", Map.of("ids", List.of(restock.path("id").asText())), 200));
        assertEquals(1, read.path("updated").asInt()); assertEquals(2, read.path("unread").asInt());
        assertEquals(0, data(ok(c, "POST", "/customer/notifications/read", Map.of("all", true), 200)).path("unread").asInt());
        assertEquals(0, data(ok(c, "GET", "/customer/notifications?unread=true", null, 200)).path("total").asInt());
        // 取消訂閱後不再通知：售完再補貨
        ok(c, "PUT", "/customer/product-alerts/" + f.product(), Map.of("kinds", List.of()), 200);
        jdbc.update("UPDATE product_alert_state SET in_stock=FALSE WHERE product_id=?", f.product());
        assertEquals(0, scanner.scanOne(f.product()));
    }

    private static List<String> toList(JsonNode n) { var out = new ArrayList<String>(); n.forEach(x -> out.add(x.asText())); return out; }
    private static JsonNode find(JsonNode inbox, String kind) { for (var n : inbox.path("items")) if (n.path("kind").asText().equals(kind)) return n; throw new AssertionError(kind); }
}
