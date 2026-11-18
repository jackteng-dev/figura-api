package dev.jackteng.figura;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Phase 3：現貨＋預購同一購物車、伺服器分組報價、單一交易拆單（全部成功或全部回滾）。 */
@SpringBootTest(properties = "shop.orders.expiry-delay-ms=600000") @AutoConfigureMockMvc(print = MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "SHOP_MYSQL_INTEGRATION", matches = "true")
class UnifiedCheckoutIntegrationTest extends ShopIntegrationSupport {
    private int reserved(Fixture f) { return jdbc.queryForObject("SELECT reserved FROM sku_inventory WHERE sku_id=?", Integer.class, f.sku()); }
    private int held(JsonNode b) { return jdbc.queryForObject("SELECT held_quantity FROM preorder_batch WHERE id=?", Integer.class, b.path("id").asText()); }
    private JsonNode addStock(Client c, Fixture f, int qty) throws Exception { return data(ok(c, "PUT", f.cart(), Map.of("expectedVersion", cart(c).path("version").asInt(), "quantity", qty), 200)); }
    private JsonNode addPreorder(Client c, JsonNode b, int qty) throws Exception {
        return data(ok(c, "PUT", "/customer/cart/preorders/" + b.path("id").asText(), Map.of("expectedVersion", cart(c).path("version").asInt(), "quantity", qty), 200));
    }
    private Map<String, Object> checkoutBody(Client c, String address) throws Exception {
        var q = data(ok(c, "GET", "/customer/checkout/quote", null, 200));
        return new HashMap<>(Map.of("requestId", uuid(), "cartVersion", q.path("cartVersion").asInt(), "addressId", address, "addressVersion", 1, "expectedTotalTwd", q.path("totalTwd").asLong()));
    }
    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }

    @Test void mixedCartSplitsIntoOneStockOrderAndOneOrderPerBatchInOneTransaction() throws Exception {
        var a = admin(); var f = fixture(a, 10, 500); var g = fixture(a, 0, 900);
        var b1 = batch(a, g, 10, 900, true); var b2 = batch(a, g, 10, 1100, true); // 同一 SKU 兩個批次
        var c = customer(); String address = newAddress(c);
        addStock(c, f, 2); addPreorder(c, b1, 1); var cart = addPreorder(c, b2, 3);
        assertEquals(1, cart.path("items").size()); assertEquals(2, cart.path("preorders").size(), "同 SKU 不同批次是兩個品項");
        assertEquals(1000, cart.path("subtotalTwd").asLong()); assertEquals(900 + 3300, cart.path("preorderSubtotalTwd").asLong()); assertEquals(5200, cart.path("totalTwd").asLong());
        assertTrue(cart.path("checkoutReady").asBoolean());

        var quote = data(ok(c, "GET", "/customer/checkout/quote", null, 200));
        assertEquals(3, quote.path("groups").size());
        assertEquals("STOCK", quote.path("groups").get(0).path("kind").asText());
        assertEquals(5200, quote.path("totalTwd").asLong()); assertEquals(5200, quote.path("payNowTwd").asLong());
        assertEquals(0, reserved(f)); assertEquals(0, held(b1)); // 預覽不保留

        var body = checkoutBody(c, address);
        var placed = data(ok(c, "POST", "/customer/checkout", body, 200));
        assertEquals(3, placed.path("orders").size()); assertFalse(placed.path("replayed").asBoolean());
        assertEquals(5200, placed.path("totalTwd").asLong());
        assertEquals(2, reserved(f)); assertEquals(1, held(b1)); assertEquals(3, held(b2));
        var after = cart(c);
        assertEquals(0, after.path("items").size()); assertEquals(0, after.path("preorders").size());
        assertEquals(3, count("SELECT COUNT(*) FROM checkout_group_order WHERE group_id=?", placed.path("groupId").asText()));
        // 子單都出現在統一訂單中心
        assertEquals(3, data(ok(c, "GET", "/customer/order-center", null, 200)).path("counts").path("PENDING_PAYMENT").asInt());

        // 同一 requestId 重送：回傳同一組，不再保留
        var replay = data(ok(c, "POST", "/customer/checkout", body, 200));
        assertTrue(replay.path("replayed").asBoolean()); assertEquals(placed.path("groupId").asText(), replay.path("groupId").asText());
        assertEquals(2, reserved(f)); assertEquals(1, held(b1));
        var changed = new HashMap<>(body); changed.put("expectedTotalTwd", 1L);
        assertEquals(409, call(c, "POST", "/customer/checkout", changed).getResponse().getStatus());
    }

    @Test void anyFailingPreorderRollsBackTheStockReservationToo() throws Exception {
        var a = admin(); var f = fixture(a, 10, 500); var g = fixture(a, 0, 900); var b = batch(a, g, 2, 900, true);
        var c = customer(); var other = customer(); String address = newAddress(c);
        addStock(c, f, 3); addPreorder(c, b, 2);
        var body = checkoutBody(c, address);
        preorder(other, b, 1, 900); // 別人先占走配額：剩 1，購物車要 2
        var r = call(c, "POST", "/customer/checkout", body);
        assertEquals(409, r.getResponse().getStatus(), text(r));
        assertEquals(0, reserved(f), "現貨保留必須跟著回滾"); assertEquals(1, held(b));
        assertEquals(0, count("SELECT COUNT(*) FROM customer_order WHERE customer_id=?", c.id()));
        assertEquals(0, count("SELECT COUNT(*) FROM checkout_group WHERE customer_id=?", c.id()));
        var still = cart(c);
        assertEquals(1, still.path("items").size()); assertEquals(1, still.path("preorders").size()); // 購物車保留
        assertFalse(still.path("checkoutReady").asBoolean());
        assertEquals("INSUFFICIENT_QUOTA", still.path("preorders").get(0).path("issue").asText());
    }

    @Test void priceChangeOrStaleCartVersionCreatesNothing() throws Exception {
        var a = admin(); var f = fixture(a, 10, 500); var c = customer(); String address = newAddress(c);
        addStock(c, f, 1);
        var body = checkoutBody(c, address);
        var wrong = new HashMap<>(body); wrong.put("expectedTotalTwd", 499L); wrong.put("requestId", uuid());
        assertEquals(409, call(c, "POST", "/customer/checkout", wrong).getResponse().getStatus());
        addStock(c, f, 2); // 版本變了
        assertEquals(409, call(c, "POST", "/customer/checkout", body).getResponse().getStatus());
        assertEquals(0, reserved(f));
        assertEquals(0, count("SELECT COUNT(*) FROM customer_order WHERE customer_id=?", c.id()));
        // 未知欄位、空購物車、別人的地址
        var extra = checkoutBody(c, address); extra.put("unitPriceTwd", 1);
        assertEquals(400, call(c, "POST", "/customer/checkout", extra).getResponse().getStatus());
        var d = customer(); var foreign = checkoutBody(c, newAddress(d));
        assertEquals(404, call(c, "POST", "/customer/checkout", foreign).getResponse().getStatus());
        assertEquals(400, call(d, "POST", "/customer/checkout", checkoutBody(d, newAddress(d))).getResponse().getStatus()); // 空購物車版本為 0，請求驗證即拒絕
        assertEquals(401, call(guest(), "GET", "/customer/checkout/quote", null).getResponse().getStatus());
    }

    @Test void preorderCartLinesValidateBatchStateQuotaAndVersion() throws Exception {
        var a = admin(); var g = fixture(a, 0, 900); var open = batch(a, g, 3, 900, true); var draft = batch(a, g, 3, 900, false);
        var c = customer();
        assertEquals(409, call(c, "PUT", "/customer/cart/preorders/" + draft.path("id").asText(), Map.of("expectedVersion", cart(c).path("version").asInt(), "quantity", 1)).getResponse().getStatus());
        assertEquals(409, call(c, "PUT", "/customer/cart/preorders/" + open.path("id").asText(), Map.of("expectedVersion", cart(c).path("version").asInt(), "quantity", 4)).getResponse().getStatus());
        assertEquals(404, call(c, "PUT", "/customer/cart/preorders/" + uuid(), Map.of("expectedVersion", cart(c).path("version").asInt(), "quantity", 1)).getResponse().getStatus());
        var v = addPreorder(c, open, 2).path("version").asInt();
        assertEquals(409, call(c, "PUT", "/customer/cart/preorders/" + open.path("id").asText(), Map.of("expectedVersion", v - 1, "quantity", 1)).getResponse().getStatus());
        assertEquals(0, held(open)); // 購物車不保留配額
        var removed = data(ok(c, "DELETE", "/customer/cart/preorders/" + open.path("id").asText() + "?expectedVersion=" + v, null, 200));
        assertEquals(0, removed.path("preorders").size());
    }

    @Test void legacyCartCheckoutStillOrdersOnlyStockAndKeepsPreorderLines() throws Exception {
        var a = admin(); var f = fixture(a, 5, 500); var g = fixture(a, 0, 900); var b = batch(a, g, 5, 900, true);
        var c = customer(); String address = newAddress(c);
        addStock(c, f, 1); var cart = addPreorder(c, b, 1);
        var r = ok(c, "POST", "/customer/orders", Map.of("requestId", uuid(), "cartVersion", cart.path("version").asInt(), "addressId", address, "addressVersion", 1, "expectedSubtotalTwd", 500L), 200);
        assertEquals(1, data(r).path("order").path("items").size());
        assertEquals(1, reserved(f)); assertEquals(0, held(b));
        var after = cart(c); assertEquals(0, after.path("items").size()); assertEquals(1, after.path("preorders").size());
    }

    @Test void preorderOnlyCartAndConcurrentLastQuotaHaveExactlyOneWinner() throws Exception {
        var a = admin(); var f = fixture(a, 10, 500); var g = fixture(a, 0, 900); var b = batch(a, g, 1, 900, true);
        var c = customer(); var d = customer(); String ac = newAddress(c), ad = newAddress(d);
        addStock(c, f, 1); addPreorder(c, b, 1); addStock(d, f, 1); addPreorder(d, b, 1);
        var bc = checkoutBody(c, ac); var bd = checkoutBody(d, ad);
        var pool = Executors.newFixedThreadPool(2); var gate = new CountDownLatch(1);
        try {
            var x = pool.submit(() -> { gate.await(); return call(c, "POST", "/customer/checkout", bc).getResponse().getStatus(); });
            var y = pool.submit(() -> { gate.await(); return call(d, "POST", "/customer/checkout", bd).getResponse().getStatus(); });
            gate.countDown();
            var statuses = List.of(x.get(30, TimeUnit.SECONDS), y.get(30, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(s -> s == 200).count(), statuses.toString());
            assertEquals(1, statuses.stream().filter(s -> s == 409).count(), statuses.toString());
        } finally { pool.shutdownNow(); }
        assertEquals(1, held(b)); assertEquals(1, reserved(f), "輸的一方現貨不保留");
        // 只有預購的購物車
        var e = customer(); var b2 = batch(a, g, 5, 900, true); addPreorder(e, b2, 2);
        var placed = data(ok(e, "POST", "/customer/checkout", checkoutBody(e, newAddress(e)), 200));
        assertEquals(1, placed.path("orders").size()); assertEquals("PREORDER", placed.path("orders").get(0).path("kind").asText());
        assertEquals(2, held(b2));
    }
}
