package dev.jackteng.figura;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Phase 5：配送方式與運費、合併／分開出貨、超商取貨（測試門市）、出貨單整張出貨（測試物流）。 */
// 模擬旗標由環境變數開啟；共用既有預購測試 context（MySQL 連線數）
@SpringBootTest(properties="shop.orders.expiry-delay-ms=600000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "SHOP_MYSQL_INTEGRATION", matches = "true")
class ShippingIntegrationTest extends ShopIntegrationSupport {
    private JsonNode quote(Client c, String method, String strategy) throws Exception {
        return data(ok(c, "GET", "/customer/checkout/quote?shippingMethod=" + method + "&deliveryStrategy=" + strategy, null, 200));
    }
    private Map<String, Object> place(Client c, String address, String method, String strategy, String store) throws Exception {
        var q = quote(c, method, strategy);
        var body = new HashMap<String, Object>(Map.of("requestId", uuid(), "cartVersion", q.path("cartVersion").asInt(), "addressId", address, "addressVersion", 1,
                "expectedTotalTwd", q.path("totalTwd").asLong(), "shippingMethod", method, "deliveryStrategy", strategy));
        if (store != null) body.put("pickupStoreId", store);
        return body;
    }
    private void addStock(Client c, Fixture f, int qty) throws Exception { ok(c, "PUT", f.cart(), Map.of("expectedVersion", cart(c).path("version").asInt(), "quantity", qty), 200); }
    private void addPre(Client c, JsonNode b, int qty) throws Exception { ok(c, "PUT", "/customer/cart/preorders/" + b.path("id").asText(), Map.of("expectedVersion", cart(c).path("version").asInt(), "quantity", qty), 200); }
    private JsonNode stockOrder(Client c, String id) throws Exception { return data(ok(c, "GET", "/customer/orders/" + id, null, 200)); }
    private JsonNode preOrder(Client c, String id) throws Exception { return data(ok(c, "GET", "/customer/preorders/" + id, null, 200)); }
    private void payStock(Client c, String id) throws Exception {
        var o = stockOrder(c, id);
        var p = data(ok(c, "POST", "/customer/orders/" + id + "/mock-payments", Map.of("requestId", uuid(), "expectedOrderVersion", o.path("version").asInt()), 200)).path("payment");
        assertEquals(o.path("totalTwd").asLong(), p.path("amountTwd").asLong(), "付款金額含運費");
        ok(c, "POST", "/customer/mock-payments/" + p.path("id").asText() + "/simulate", Map.of("eventId", uuid(), "outcome", "SUCCESS"), 200);
    }
    private void payPre(Client c, String id) throws Exception {
        var o = preOrder(c, id);
        var p = data(ok(c, "POST", "/customer/preorders/" + id + "/mock-payments", Map.of("requestId", uuid(), "expectedOrderVersion", o.path("version").asInt()), 200)).path("payment");
        ok(c, "POST", "/customer/preorder-payments/" + p.path("id").asText() + "/simulate", Map.of("eventId", uuid(), "outcome", "SUCCESS"), 200);
    }
    private void arrive(Client a, JsonNode b, String order) throws Exception {
        var sup = data(ok(a, "GET", "/admin/preorder-arrivals/" + b.path("id").asText(), null, 200)).path("supply");
        ok(a, "POST", "/admin/preorder-arrivals/" + b.path("id").asText() + "/receive", Map.of("requestId", uuid(), "expectedBatchVersion", sup.path("version").asInt(), "quantity", 1, "note", "到貨"), 200);
        var s = data(ok(a, "GET", "/admin/preorder-orders/" + order, null, 200)).path("order");
        sup = data(ok(a, "GET", "/admin/preorder-arrivals/" + b.path("id").asText(), null, 200)).path("supply");
        ok(a, "POST", "/admin/preorder-orders/" + order + "/allocate", Map.of("requestId", uuid(), "expectedOrderVersion", s.path("version").asInt(), "expectedBatchVersion", sup.path("version").asInt(), "note", "分配"), 200);
    }
    private JsonNode shipment(Client a, String id) throws Exception { return data(ok(a, "GET", "/admin/shipments/" + id, null, 200)); }
    private Map<String, Object> command(JsonNode detail, String note) { return new HashMap<>(Map.of("requestId", uuid(), "expectedVersion", detail.path("shipment").path("version").asInt(), "note", note)); }
    private Client staff() throws Exception {
        var c = guest(); String device = uuid();
        var r = mvc.perform(post("/figura/api/admin/login").contextPath("/figura").servletPath("/api/admin/login").cookie(c.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN", c.csrf())
                .param("username", "shop_staff").param("password", System.getenv("DEMO_STAFF_PASSWORD")).param("deviceId", device)).andReturn();
        assertEquals(200, r.getResponse().getStatus()); String id = data(r).path("id").asText(); sessions.add("shop:session:" + id + ":" + device);
        return new Client(c.csrf(), List.of(c.cookies().get(0), cookie(r, "accessToken"), cookie(r, "refreshToken")), id);
    }

    @Test void quoteFeesFollowMethodStrategyAndFreeThreshold() throws Exception {
        var a = admin(); var f = fixture(a, 10, 1000); var g = fixture(a, 0, 900); var b = batch(a, g, 5, 900, true);
        var c = customer(); addStock(c, f, 1); addPre(c, b, 1);
        var split = quote(c, "HOME", "SPLIT");
        assertEquals(2, split.path("shipments").size()); assertEquals(240, split.path("shippingTwd").asInt()); assertEquals(1900 + 240, split.path("totalTwd").asLong());
        var combined = quote(c, "HOME", "COMBINED");
        assertEquals(1, combined.path("shipments").size()); assertEquals(120, combined.path("shippingTwd").asInt());
        assertEquals("STOCK", combined.path("shipments").get(0).path("carrierKey").asText()); assertEquals(120, combined.path("groups").get(0).path("shippingTwd").asInt());
        assertEquals(0, combined.path("groups").get(1).path("shippingTwd").asInt());
        assertEquals(120, quote(c, "CVS", "SPLIT").path("shippingTwd").asInt());
        assertEquals(0, quote(c, "CVS", "COMBINED").path("shippingTwd").asInt(), "合併後 1900 達超商免運門檻 1500");
        assertEquals(2, quote(c, "HOME", "SPLIT").path("methods").size());
        assertEquals(400, call(c, "GET", "/customer/checkout/quote?shippingMethod=AIR", null).getResponse().getStatus());
        assertEquals(5, data(ok(c, "GET", "/customer/checkout/pickup-stores", null, 200)).size());
        // 只有一組時合併等於分開
        var d = customer(); addStock(d, f, 1);
        assertEquals("SPLIT", quote(d, "HOME", "COMBINED").path("deliveryStrategy").asText());
    }

    @Test void combinedCvsShipmentWaitsForPreorderThenShipsAsOneParcel() throws Exception {
        var a = admin(); var f = fixture(a, 10, 1000); var g = fixture(a, 0, 900); var b = batch(a, g, 5, 900, true);
        var c = customer(); String address = newAddress(c); addStock(c, f, 1); addPre(c, b, 1);
        assertEquals(400, call(c, "POST", "/customer/checkout", place(c, address, "CVS", "COMBINED", null)).getResponse().getStatus(), "超商需選門市");
        assertEquals(400, call(c, "POST", "/customer/checkout", place(c, address, "CVS", "COMBINED", "REAL-STORE-1")).getResponse().getStatus(), "只接受測試門市");
        var placed = data(ok(c, "POST", "/customer/checkout", place(c, address, "CVS", "COMBINED", "TEST-711-001"), 200));
        String stock = placed.path("orders").get(0).path("id").asText(), pre = placed.path("orders").get(1).path("id").asText();
        var so = stockOrder(c, stock);
        assertEquals(0, so.path("shippingTwd").asInt(), "合併 1900 超商免運");
        var sh = so.path("shipment");
        assertEquals("CVS", sh.path("method").asText()); assertEquals("COMBINED", sh.path("strategy").asText()); assertEquals(2, sh.path("orders").size());
        assertEquals("TEST-711-001", sh.path("destination").path("storeId").asText()); assertTrue(sh.path("destination").path("test").asBoolean());
        assertEquals("WAITING", sh.path("status").asText()); assertEquals("PAYMENT", sh.path("waitingFor").asText());
        assertEquals(sh.path("id").asText(), preOrder(c, pre).path("shipment").path("id").asText());

        payStock(c, stock); payPre(c, pre);
        String id = sh.path("id").asText();
        assertEquals("ARRIVAL", shipment(a, id).path("shipment").path("waitingFor").asText(), "現貨等預購到貨");
        assertFalse(shipment(a, id).path("canShip").asBoolean());
        // 合併出貨單的現貨不能單筆出貨
        var single = data(ok(a, "GET", "/admin/orders/" + stock, null, 200));
        assertFalse(single.path("canShip").asBoolean());
        arrive(a, b, pre);
        var ready = shipment(a, id);
        assertEquals("READY", ready.path("shipment").path("status").asText()); assertTrue(ready.path("canShip").asBoolean());
        var body = command(ready, "整張出貨");
        var shipped = data(ok(a, "POST", "/admin/shipments/" + id + "/ship", body, 201));
        String tracking = shipped.path("event").path("trackingNumber").asText();
        assertTrue(tracking.startsWith("MOCK-"));
        assertEquals("SHIPPED", shipped.path("current").path("shipment").path("status").asText());
        assertEquals(tracking, stockOrder(c, stock).path("fulfillment").path("trackingId").asText());
        assertEquals(tracking, preOrder(c, pre).path("fulfillment").path("trackingNumber").asText());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE order_id=? AND event_kind='ORDER_SHIPPED'", Long.class, stock));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE preorder_order_id=? AND event_kind='PREORDER_SHIPPED'", Long.class, pre));
        assertEquals(1, jdbc.queryForObject("SELECT shipped_quantity FROM preorder_batch WHERE id=?", Integer.class, b.path("id").asText()));
        assertTrue(data(ok(a, "POST", "/admin/shipments/" + id + "/ship", body, 200)).path("replayed").asBoolean());
        var changed = new HashMap<>(body); changed.put("note", "不同");
        assertEquals(409, call(a, "POST", "/admin/shipments/" + id + "/ship", changed).getResponse().getStatus());
        var delivered = data(ok(a, "POST", "/admin/shipments/" + id + "/deliver", command(shipment(a, id), "送達"), 201));
        assertEquals("DELIVERED", delivered.path("current").path("shipment").path("status").asText());
        assertEquals("DELIVERED", preOrder(c, pre).path("fulfillmentStatus").asText());
        assertEquals("COMPLETED", data(ok(c, "GET", "/customer/order-center?kind=STOCK", null, 200)).path("items").get(0).path("stage").asText());
    }

    @Test void splitHomeChargesEachOrderAndDepositFeeGoesToBalance() throws Exception {
        var a = admin(); var f = fixture(a, 10, 1000); var g = fixture(a, 0, 3000);
        var now = java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(8)).withNano(0);
        var draft = data(ok(a, "POST", "/admin/preorders", new HashMap<String, Object>(Map.ofEntries(Map.entry("productId", g.product()), Map.entry("skuId", g.sku()), Map.entry("name", "Deposit " + uuid()),
                Map.entry("description", "合成"), Map.entry("priceTwd", 2000), Map.entry("capacity", 5), Map.entry("deadlineAt", now.plusDays(1).toString()),
                Map.entry("arrivalFrom", now.plusDays(30).toLocalDate().toString()), Map.entry("arrivalTo", now.plusDays(40).toLocalDate().toString()), Map.entry("expectedVersion", 0),
                Map.entry("requestId", uuid()), Map.entry("note", "合成"), Map.entry("paymentMode", "DEPOSIT"), Map.entry("depositTwd", 500), Map.entry("balanceDays", 7))), 201)).path("current");
        var dep = data(ok(a, "POST", "/admin/preorders/" + draft.path("id").asText() + "/open", Map.of("expectedVersion", draft.path("version").asInt(), "requestId", uuid(), "note", "開放"), 201)).path("current");
        var c = customer(); String address = newAddress(c); addStock(c, f, 1); addPre(c, dep, 1);
        var placed = data(ok(c, "POST", "/customer/checkout", place(c, address, "HOME", "SPLIT", null), 200));
        assertEquals(1000 + 120 + 2000 + 120, placed.path("totalTwd").asLong());
        String stock = placed.path("orders").get(0).path("id").asText(), pre = placed.path("orders").get(1).path("id").asText();
        assertEquals(120, stockOrder(c, stock).path("shippingTwd").asInt());
        var po = preOrder(c, pre);
        assertEquals(120, po.path("shippingTwd").asInt()); assertEquals(500, po.path("depositTwd").asLong()); assertEquals(1620, po.path("balanceTwd").asLong(), "運費加在尾款");
        assertEquals(500, po.path("payNowTwd").asLong());
        assertNotEquals(stockOrder(c, stock).path("shipment").path("id").asText(), po.path("shipment").path("id").asText(), "分開寄各一張出貨單");
        // 單一訂單出貨單：舊的單筆出貨仍可用，出貨單狀態跟著訂單
        payStock(c, stock);
        var s = data(ok(a, "GET", "/admin/orders/" + stock, null, 200));
        assertTrue(s.path("canShip").asBoolean());
        ok(a, "POST", "/admin/orders/" + stock + "/mock-shipment", Map.of("requestId", uuid(), "expectedOrderVersion", s.path("order").path("version").asInt(), "note", "單筆出貨"), 200);
        String sid = stockOrder(c, stock).path("shipment").path("id").asText();
        var view = shipment(a, sid);
        assertEquals("SHIPPED", view.path("shipment").path("status").asText()); assertTrue(view.path("shipment").path("trackingNumber").asText().startsWith("MOCK-"));
        assertEquals("DELIVERED", data(ok(a, "POST", "/admin/shipments/" + sid + "/deliver", command(view, "送達"), 201)).path("current").path("shipment").path("status").asText());
        // 舊客戶端沒送配送欄位：預設宅配分開寄，金額需含運費
        var d = customer(); String da = newAddress(d); addStock(d, f, 1);
        var q = quote(d, "HOME", "SPLIT");
        var legacy = Map.<String, Object>of("requestId", uuid(), "cartVersion", q.path("cartVersion").asInt(), "addressId", da, "addressVersion", 1, "expectedTotalTwd", 1000L);
        assertEquals(409, call(d, "POST", "/customer/checkout", legacy).getResponse().getStatus(), "不含運費的舊金額要重新核對");
        var fixed = new HashMap<>(legacy); fixed.put("expectedTotalTwd", q.path("totalTwd").asLong());
        assertEquals(120, stockOrder(d, data(ok(d, "POST", "/customer/checkout", fixed, 200)).path("orders").get(0).path("id").asText()).path("shippingTwd").asInt());
    }

    /** 承擔運費的現貨單在出貨前取消：其餘預購到貨後需店長明確免收運費才能出貨；有待核對付款時不能送達。 */
    @Test void carrierCancelledNeedsExplicitFeeWaiverAndFlaggedOrdersCannotBeDelivered() throws Exception {
        var a = admin(); var f = fixture(a, 10, 1000); var g = fixture(a, 0, 900); var b = batch(a, g, 5, 900, true);
        var c = customer(); String address = newAddress(c); addStock(c, f, 1); addPre(c, b, 1);
        var placed = data(ok(c, "POST", "/customer/checkout", place(c, address, "HOME", "COMBINED", null), 200));
        String stock = placed.path("orders").get(0).path("id").asText(), pre = placed.path("orders").get(1).path("id").asText();
        assertEquals(120, stockOrder(c, stock).path("shippingTwd").asInt());
        payPre(c, pre);
        ok(c, "POST", "/customer/orders/" + stock + "/cancel", Map.of("expectedVersion", stockOrder(c, stock).path("version").asInt()), 200);
        arrive(a, b, pre);
        String id = preOrder(c, pre).path("shipment").path("id").asText();
        var d = shipment(a, id);
        assertEquals("WAITING", d.path("shipment").path("status").asText()); assertEquals("FEE", d.path("shipment").path("waitingFor").asText());
        assertTrue(d.path("canShip").asBoolean(), "店長可以決定免收後出貨");
        assertEquals(409, call(a, "POST", "/admin/shipments/" + id + "/ship", command(d, "出貨")).getResponse().getStatus(), "未明確免收不能出貨");
        var waive = command(d, "顧客取消現貨，店長同意免收"); waive.put("waiveUnpaidFee", true);
        var shipped = data(ok(a, "POST", "/admin/shipments/" + id + "/ship", waive, 201));
        assertTrue(shipped.path("event").path("note").asText().startsWith("【免收未付運費】"));
        assertEquals("SHIPPED", shipped.path("current").path("shipment").path("status").asText());
        assertTrue(data(ok(a, "POST", "/admin/shipments/" + id + "/ship", waive, 200)).path("replayed").asBoolean());
        // 出貨後出現未處理的付款核對：暫停送達
        String payment = jdbc.queryForObject("SELECT id FROM preorder_mock_payment WHERE order_id=? AND status='SUCCEEDED'", String.class, pre);
        String extra = uuid();
        jdbc.update("INSERT INTO preorder_mock_payment(id,order_id,customer_id,request_id,expected_order_version,transaction_id,amount_twd,status,settled_at,stage) VALUES(?,?,?,?,1,?,900,'REVIEW_REQUIRED',CURRENT_TIMESTAMP(6),'FULL')", extra, pre, c.id(), uuid(), "mock_" + extra);
        var flagged = shipment(a, id);
        assertFalse(flagged.path("canDeliver").asBoolean());
        assertEquals(409, call(a, "POST", "/admin/shipments/" + id + "/deliver", command(flagged, "送達")).getResponse().getStatus());
        assertNotNull(payment);
    }

    @Test void feeRulesAreAuditedVersionedAndStaffCannotChangeThem() throws Exception {
        var a = admin(); var methods = data(ok(a, "GET", "/admin/shipping-methods", null, 200));
        var home = methods.get(0); assertEquals("HOME", home.path("code").asText());
        var body = new HashMap<String, Object>(Map.of("requestId", uuid(), "expectedVersion", home.path("version").asInt(), "feeTwd", 150, "freeThresholdTwd", 5000, "enabled", true, "note", "調整運費"));
        try {
            var r = data(ok(a, "PUT", "/admin/shipping-methods/HOME", body, 200));
            assertEquals(150, r.path("current").path("feeTwd").asInt()); assertFalse(r.path("replayed").asBoolean());
            assertTrue(data(ok(a, "PUT", "/admin/shipping-methods/HOME", body, 200)).path("replayed").asBoolean());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM shipping_method_event WHERE request_id=?", Integer.class, body.get("requestId")));
            var stale = new HashMap<>(body); stale.put("requestId", uuid());
            assertEquals(409, call(a, "PUT", "/admin/shipping-methods/HOME", stale).getResponse().getStatus());
            var s = staff();
            assertEquals(200, call(s, "GET", "/admin/shipping-methods", null).getResponse().getStatus());
            assertEquals(403, call(s, "PUT", "/admin/shipping-methods/HOME", stale).getResponse().getStatus());
            // 停用的配送方式：報價不可結帳、下單 409
            var f = fixture(a, 5, 1000); var c = customer(); String address = newAddress(c); addStock(c, f, 1);
            var cvs = data(ok(a, "GET", "/admin/shipping-methods", null, 200)).get(1);
            var off = new HashMap<String, Object>(Map.of("requestId", uuid(), "expectedVersion", cvs.path("version").asInt(), "feeTwd", 60, "freeThresholdTwd", 1500, "enabled", false, "note", "暫停"));
            ok(a, "PUT", "/admin/shipping-methods/CVS", off, 200);
            assertFalse(quote(c, "CVS", "SPLIT").path("ready").asBoolean());
            assertEquals(409, call(c, "POST", "/customer/checkout", place(c, address, "CVS", "SPLIT", "TEST-FM-001")).getResponse().getStatus());
        } finally {
            jdbc.update("UPDATE shipping_method SET fee_twd=120,free_threshold_twd=3000,enabled=TRUE WHERE code='HOME'");
            jdbc.update("UPDATE shipping_method SET fee_twd=60,free_threshold_twd=1500,enabled=TRUE WHERE code='CVS'");
        }
    }
}
