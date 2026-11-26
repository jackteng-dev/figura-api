package dev.jackteng.figura;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jackteng.figura.service.WarehouseService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Commerce V2 Phase 5：出貨單合併（不重複收費、不動付款與庫存）、私人倉庫暫存、到期提醒、運費差額審核。 */
@SpringBootTest(properties = "shop.orders.expiry-delay-ms=600000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "SHOP_MYSQL_INTEGRATION", matches = "true")
class WarehouseIntegrationTest extends ShopIntegrationSupport {
    @Autowired WarehouseService warehouse;

    private String checkout(Client c, Fixture f, String address) throws Exception {
        ok(c, "PUT", f.cart(), Map.of("expectedVersion", cart(c).path("version").asInt(), "quantity", 1), 200);
        var q = data(ok(c, "GET", "/customer/checkout/quote?shippingMethod=HOME&deliveryStrategy=SPLIT", null, 200));
        var placed = data(ok(c, "POST", "/customer/checkout", Map.of("requestId", uuid(), "cartVersion", q.path("cartVersion").asInt(), "addressId", address, "addressVersion", 1,
                "expectedTotalTwd", q.path("totalTwd").asLong(), "shippingMethod", "HOME", "deliveryStrategy", "SPLIT"), 200));
        return placed.path("orders").get(0).path("id").asText();
    }
    private JsonNode order(Client c, String id) throws Exception { return data(ok(c, "GET", "/customer/orders/" + id, null, 200)); }
    private void pay(Client c, String id) throws Exception {
        var o = order(c, id);
        var p = data(ok(c, "POST", "/customer/orders/" + id + "/mock-payments", Map.of("requestId", uuid(), "expectedOrderVersion", o.path("version").asInt()), 200)).path("payment");
        ok(c, "POST", "/customer/mock-payments/" + p.path("id").asText() + "/simulate", Map.of("eventId", uuid(), "outcome", "SUCCESS"), 200);
    }
    private JsonNode mine(Client c) throws Exception { return data(ok(c, "GET", "/customer/shipments", null, 200)); }
    private JsonNode find(JsonNode page, String shipmentId) { for (var i : page.path("items")) if (i.path("shipment").path("id").asText().equals(shipmentId)) return i; return null; }
    private Map<String, Object> ref(JsonNode s) { return Map.of("id", s.path("id").asText(), "expectedVersion", s.path("version").asInt()); }
    private Client staff() throws Exception {
        var c = guest(); String device = uuid();
        var r = StaffLogin.complete(mvc, () -> mvc.perform(post("/figura/api/admin/login").contextPath("/figura").servletPath("/api/admin/login").cookie(c.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN", c.csrf())
                .param("username", "shop_staff").param("password", System.getenv("DEMO_STAFF_PASSWORD")).param("deviceId", device)).andReturn());
        assertEquals(200, r.getResponse().getStatus()); String id = data(r).path("id").asText(); sessions.add("shop:session:" + id + ":" + device);
        return new Client(c.csrf(), List.of(c.cookies().get(0), cookie(r, "accessToken"), cookie(r, "refreshToken")), id);
    }

    @Test void mergeHoldRemindAndAdjust() throws Exception {
        var a = admin(); var c = customer(); String address = newAddress(c);
        var f = fixture(a, 10, 1000); var g = fixture(a, 10, 1000);
        String o1 = checkout(c, f, address), o2 = checkout(c, g, address);
        pay(c, o1); pay(c, o2);
        JsonNode s1 = order(c, o1).path("shipment"), s2 = order(c, o2).path("shipment");
        assertEquals(120, s1.path("feeTwd").asInt()); assertEquals(120, s2.path("feeTwd").asInt());
        var list = mine(c);
        assertEquals(2, list.path("total").asInt()); assertTrue(find(list, s1.path("id").asText()).path("mergeable").asBoolean());

        // 舊版本拒絕；正確版本合併
        var stale = Map.of("requestId", uuid(), "shipments", List.of(Map.of("id", s1.path("id").asText(), "expectedVersion", 99), ref(s2)));
        assertEquals(409, call(c, "POST", "/customer/shipments/merge", stale).getResponse().getStatus());
        String request = uuid();
        var body = Map.of("requestId", request, "shipments", List.of(ref(s1), ref(s2)));
        var merged = data(ok(c, "POST", "/customer/shipments/merge", body, 200));
        var ns = merged.path("shipment").path("shipment");
        assertEquals("READY", ns.path("status").asText()); assertEquals(2, ns.path("orders").size()); assertEquals("COMBINED", ns.path("strategy").asText());
        assertEquals(120, ns.path("feeTwd").asInt(), "合併後 2000 元仍未達免運，只收一次 120");
        var adj = merged.path("adjustment");
        assertEquals(240, adj.path("beforeFeeTwd").asInt()); assertEquals(120, adj.path("creditTwd").asInt()); assertEquals("PENDING_REVIEW", adj.path("status").asText());
        // 訂單金額與付款不變；舊出貨單從清單消失
        assertEquals(120, order(c, o1).path("shippingTwd").asInt()); assertEquals("PAID", order(c, o2).path("status").asText());
        assertEquals(ns.path("id").asText(), order(c, o1).path("shipment").path("id").asText());
        assertEquals(1, mine(c).path("total").asInt());
        var replay = data(ok(c, "POST", "/customer/shipments/merge", body, 200));
        assertTrue(replay.path("replayed").asBoolean()); assertEquals(ns.path("id").asText(), replay.path("shipment").path("shipment").path("id").asText());
        assertEquals(409, call(c, "POST", "/customer/shipments/merge", Map.of("requestId", request, "shipments", List.of(ref(s1), Map.of("id", uuid(), "expectedVersion", 1)))).getResponse().getStatus());
        var old = data(ok(a, "GET", "/admin/shipments/" + s1.path("id").asText(), null, 200)).path("shipment");
        assertEquals("MERGED", old.path("status").asText()); assertEquals(ns.path("id").asText(), old.path("mergedInto").asText());
        // 其他顧客不能操作
        var other = customer();
        assertEquals(404, call(other, "POST", "/customer/shipments/" + ns.path("id").asText() + "/hold", Map.of("requestId", uuid(), "expectedVersion", ns.path("version").asInt())).getResponse().getStatus());

        // 暫存：不可出貨，單筆端點也不可
        var held = data(ok(c, "POST", "/customer/shipments/" + ns.path("id").asText() + "/hold", Map.of("requestId", uuid(), "expectedVersion", ns.path("version").asInt()), 200)).path("shipment");
        assertFalse(held.path("shipment").path("heldAt").isNull()); assertTrue(held.path("canRelease").asBoolean());
        var detail = data(ok(a, "GET", "/admin/shipments/" + ns.path("id").asText(), null, 200));
        assertFalse(detail.path("canShip").asBoolean());
        int ship = call(a, "POST", "/admin/shipments/" + ns.path("id").asText() + "/ship", Map.of("requestId", uuid(), "expectedVersion", detail.path("shipment").path("version").asInt(), "note", "出貨")).getResponse().getStatus();
        assertTrue(ship == 409 || ship == 404, "暫存中不可出貨：" + ship);
        assertFalse(data(ok(a, "GET", "/admin/orders/" + o1, null, 200)).path("canShip").asBoolean());
        // 倉庫清單、到期提醒（只寫一次）
        assertTrue(data(ok(a, "GET", "/admin/warehouse?pageSize=50", null, 200)).path("total").asInt() >= 1);
        jdbc.update("UPDATE shipment SET storage_until=CURRENT_TIMESTAMP(6)+INTERVAL 1 DAY WHERE id=?", ns.path("id").asText());
        warehouse.remind(500, 7);
        assertNotNull(jdbc.queryForObject("SELECT hold_reminded_at FROM shipment WHERE id=?", Object.class, ns.path("id").asText()));
        var inbox = data(ok(c, "GET", "/customer/notifications", null, 200));
        long reminders = 0; for (var n : inbox.path("items")) if (n.path("kind").asText().equals("WAREHOUSE_STORAGE_ENDING")) reminders++;
        assertEquals(1, reminders);
        warehouse.remind(500, 7);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE recipient_ref=? AND event_kind='WAREHOUSE_STORAGE_ENDING'", Integer.class, c.id()), "不重複提醒");
        // 員工延長：店長可、一般員工不可
        int v = data(ok(a, "GET", "/admin/shipments/" + ns.path("id").asText(), null, 200)).path("shipment").path("version").asInt();
        if (System.getenv("DEMO_STAFF_PASSWORD") != null)
            assertEquals(403, call(staff(), "POST", "/admin/warehouse/" + ns.path("id").asText() + "/extend", Map.of("requestId", uuid(), "expectedVersion", v, "note", "延長", "days", 10)).getResponse().getStatus());
        var ext = Map.of("requestId", uuid(), "expectedVersion", v, "note", "顧客要求延長", "days", 10);
        assertEquals("EXTEND_HOLD", data(ok(a, "POST", "/admin/warehouse/" + ns.path("id").asText() + "/extend", ext, 200)).path("event").path("action").asText());
        assertTrue(data(ok(a, "POST", "/admin/warehouse/" + ns.path("id").asText() + "/extend", ext, 200)).path("replayed").asBoolean());
        assertNull(jdbc.queryForObject("SELECT hold_reminded_at FROM shipment WHERE id=?", Object.class, ns.path("id").asText()), "延長後重新提醒");
        // 顧客取消暫存 → 可出貨
        var cur = find(mine(c), ns.path("id").asText()).path("shipment");
        var released = data(ok(c, "POST", "/customer/shipments/" + ns.path("id").asText() + "/release", Map.of("requestId", uuid(), "expectedVersion", cur.path("version").asInt()), 200)).path("shipment");
        assertTrue(released.path("shipment").path("heldAt").isNull()); assertTrue(released.path("canHold").asBoolean());
        assertTrue(data(ok(a, "GET", "/admin/shipments/" + ns.path("id").asText(), null, 200)).path("canShip").asBoolean() || System.getenv("MOCK_FULFILLMENT_ENABLED") == null);

        // 運費差額：只改狀態，不移動金錢；處理過不可再處理
        var decide = Map.of("requestId", uuid(), "expectedVersion", 1, "decision", "CREDIT_MOCK", "note", "合併運費差額模擬退回");
        ok(a, "POST", "/admin/shipping-adjustments/" + adj.path("id").asText() + "/decide", decide, 200);
        assertEquals("CREDITED_MOCK", jdbc.queryForObject("SELECT status FROM shipping_adjustment WHERE id=?", String.class, adj.path("id").asText()));
        assertEquals(409, call(a, "POST", "/admin/shipping-adjustments/" + adj.path("id").asText() + "/decide", Map.of("requestId", uuid(), "expectedVersion", 2, "decision", "DISMISS", "note", "再處理")).getResponse().getStatus());
        assertEquals(120, order(c, o2).path("shippingTwd").asInt(), "訂單運費不變");
    }

    @Test void differentDestinationOrUnpaidRules() throws Exception {
        var a = admin(); var c = customer();
        String addr1 = newAddress(c), addr2 = newAddress(c);
        jdbc.update("UPDATE customer_address SET street='合成路 99 號' WHERE id=?", addr2);
        var f = fixture(a, 10, 500); var g = fixture(a, 10, 500);
        String o1 = checkout(c, f, addr1), o2 = checkout(c, g, addr2);
        JsonNode s1 = order(c, o1).path("shipment"), s2 = order(c, o2).path("shipment");
        var r = call(c, "POST", "/customer/shipments/merge", Map.of("requestId", uuid(), "shipments", List.of(ref(s1), ref(s2))));
        assertEquals(409, r.getResponse().getStatus(), text(r));
        assertEquals(400, call(c, "POST", "/customer/shipments/merge", Map.of("requestId", uuid(), "shipments", List.of(ref(s1)))).getResponse().getStatus(), "至少兩張");
        // 未付款也能暫存（等付款期間先放倉庫），但出貨仍需付款
        var held = data(ok(c, "POST", "/customer/shipments/" + s1.path("id").asText() + "/hold", Map.of("requestId", uuid(), "expectedVersion", s1.path("version").asInt()), 200));
        assertEquals("WAITING", held.path("shipment").path("shipment").path("status").asText());
    }
}
