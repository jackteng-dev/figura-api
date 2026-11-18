package dev.jackteng.figura;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Phase 4：批次訂金／尾款、到貨後尾款通知、延期紀錄與通知、退款保留原付款紀錄。全部為模擬金流。 */
@SpringBootTest(properties = {"shop.orders.expiry-delay-ms=600000", "MOCK_PAYMENTS_ENABLED=true", "MOCK_REFUNDS_ENABLED=true", "MOCK_FULFILLMENT_ENABLED=true"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "SHOP_MYSQL_INTEGRATION", matches = "true")
class PreorderDepositIntegrationTest extends ShopIntegrationSupport {
    private static final int PRICE = 3000, DEPOSIT = 500;

    private Map<String, Object> batchBody(Fixture f, String mode, Integer deposit, Integer days) {
        var now = OffsetDateTime.now(ZoneOffset.ofHours(8)).withNano(0);
        var body = new HashMap<String, Object>(Map.ofEntries(Map.entry("productId", f.product()), Map.entry("skuId", f.sku()), Map.entry("name", "Deposit " + uuid()),
                Map.entry("description", "合成訂金預購"), Map.entry("priceTwd", PRICE), Map.entry("capacity", 10), Map.entry("deadlineAt", now.plusDays(1).toString()),
                Map.entry("arrivalFrom", now.plusDays(30).toLocalDate().toString()), Map.entry("arrivalTo", now.plusDays(40).toLocalDate().toString()),
                Map.entry("expectedVersion", 0), Map.entry("requestId", uuid()), Map.entry("note", "合成批次")));
        if (mode != null) body.put("paymentMode", mode);
        if (deposit != null) body.put("depositTwd", deposit);
        if (days != null) body.put("balanceDays", days);
        return body;
    }
    private JsonNode depositBatch(Client a, Fixture f) throws Exception {
        var b = data(ok(a, "POST", "/admin/preorders", batchBody(f, "DEPOSIT", DEPOSIT, 7), 201)).path("current");
        return data(ok(a, "POST", "/admin/preorders/" + b.path("id").asText() + "/open", Map.of("expectedVersion", b.path("version").asInt(), "requestId", uuid(), "note", "開放"), 201)).path("current");
    }
    private JsonNode batchNow(Client a, JsonNode b) throws Exception { return data(ok(a, "GET", "/admin/preorders/" + b.path("id").asText(), null, 200)).path("batch"); }
    private JsonNode order(Client c, JsonNode o) throws Exception { return data(ok(c, "GET", "/customer/preorders/" + o.path("id").asText(), null, 200)); }
    private JsonNode staff(Client a, JsonNode o) throws Exception { return data(ok(a, "GET", "/admin/preorder-orders/" + o.path("id").asText(), null, 200)); }
    private JsonNode start(Client c, JsonNode o) throws Exception {
        var fresh = order(c, o);
        return data(ok(c, "POST", "/customer/preorders/" + o.path("id").asText() + "/mock-payments", Map.of("requestId", uuid(), "expectedOrderVersion", fresh.path("version").asInt()), 200)).path("payment");
    }
    private JsonNode simulate(Client c, JsonNode p) throws Exception {
        return data(ok(c, "POST", "/customer/preorder-payments/" + p.path("id").asText() + "/simulate", Map.of("eventId", uuid(), "outcome", "SUCCESS"), 200));
    }
    private JsonNode pay(Client c, JsonNode o) throws Exception { return simulate(c, start(c, o)).path("order"); }
    private void receive(Client a, JsonNode b, int q) throws Exception {
        var supply = data(ok(a, "GET", "/admin/preorder-arrivals/" + b.path("id").asText(), null, 200)).path("supply");
        ok(a, "POST", "/admin/preorder-arrivals/" + b.path("id").asText() + "/receive", Map.of("requestId", uuid(), "expectedBatchVersion", supply.path("version").asInt(), "quantity", q, "note", "到貨"), 200);
    }
    private Map<String, Object> balanceBody(JsonNode batch, String reason) {
        return new HashMap<>(Map.of("requestId", uuid(), "expectedVersion", batch.path("version").asInt(), "publicReason", reason, "note", "內部：倉庫已點收"));
    }
    private int allocateStatus(Client a, JsonNode o, JsonNode b) throws Exception {
        var s = staff(a, o).path("order");
        var supply = data(ok(a, "GET", "/admin/preorder-arrivals/" + b.path("id").asText(), null, 200)).path("supply");
        return call(a, "POST", "/admin/preorder-orders/" + o.path("id").asText() + "/allocate", Map.of("requestId", uuid(), "expectedOrderVersion", s.path("version").asInt(), "expectedBatchVersion", supply.path("version").asInt(), "note", "分配")).getResponse().getStatus();
    }
    private long outbox(JsonNode o, String kind) { return jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE preorder_order_id=? AND event_kind=?", Long.class, o.path("id").asText(), kind); }

    @Test void depositThenBalanceAfterArrivalCallThenAllocate() throws Exception {
        var a = admin(); var f = fixture(a, 0, PRICE); var b = depositBatch(a, f);
        assertEquals("DEPOSIT", b.path("paymentMode").asText()); assertEquals(DEPOSIT, b.path("depositTwd").asInt());
        var pub = data(ok(guest(), "GET", "/catalog/preorders/" + b.path("id").asText(), null, 200));
        assertEquals("DEPOSIT", pub.path("paymentMode").asText()); assertEquals(DEPOSIT, pub.path("depositTwd").asInt()); assertEquals(7, pub.path("balanceDays").asInt());

        var c = customer(); var o = preorder(c, b, 2, PRICE);
        assertEquals("DEPOSIT", o.path("paymentMode").asText()); assertEquals(1000, o.path("depositTwd").asLong()); assertEquals(5000, o.path("balanceTwd").asLong());
        assertEquals("NOT_DUE", o.path("balanceStatus").asText()); assertEquals(1000, o.path("payNowTwd").asLong());

        var dep = start(c, o); assertEquals("DEPOSIT", dep.path("stage").asText()); assertEquals(1000, dep.path("amountTwd").asLong());
        o = simulate(c, dep).path("order");
        assertEquals("PAID", o.path("status").asText()); assertEquals("NOT_DUE", o.path("balanceStatus").asText()); assertEquals(0, o.path("payNowTwd").asLong());
        assertEquals(2, batchNow(a, b).path("paidQuantity").asInt(), "訂金成功即確保名額");
        // 尾款尚未通知：不可付尾款、不可分配
        assertFalse(data(ok(c, "GET", "/customer/preorders/" + o.path("id").asText() + "/mock-payments", null, 200)).path("canStart").asBoolean());
        assertEquals(409, call(a, "POST", "/admin/preorders/" + b.path("id").asText() + "/balance-call", balanceBody(batchNow(a, b), "已到貨")).getResponse().getStatus(), "未登記到貨不能通知尾款");
        receive(a, b, 2);
        assertEquals(409, allocateStatus(a, o, b), "訂金訂單尾款未付不可分配");

        // 員工通知尾款 → 待付尾款＋期限＋通知；同 requestId 重送不再通知
        var body = balanceBody(batchNow(a, b), "商品已到貨，請於期限內支付尾款");
        var called = data(ok(a, "POST", "/admin/preorders/" + b.path("id").asText() + "/balance-call", body, 201));
        assertEquals(1, called.path("event").path("affectedOrders").asInt()); assertEquals("BALANCE_CALL", called.path("event").path("action").asText());
        assertTrue(data(ok(a, "POST", "/admin/preorders/" + b.path("id").asText() + "/balance-call", body, 200)).path("replayed").asBoolean());
        var changed = new HashMap<>(body); changed.put("note", "不同內容");
        assertEquals(409, call(a, "POST", "/admin/preorders/" + b.path("id").asText() + "/balance-call", changed).getResponse().getStatus());
        o = order(c, o);
        assertEquals("DUE", o.path("balanceStatus").asText()); assertNotNull(o.path("balanceDueAt").asText(null)); assertEquals(5000, o.path("payNowTwd").asLong());
        assertEquals(1, outbox(o, "PREORDER_BALANCE_DUE"));
        assertEquals("PENDING_PAYMENT", data(ok(c, "GET", "/customer/order-center?kind=PREORDER", null, 200)).path("items").get(0).path("stage").asText(), "待付尾款列在待付款");

        var bal = start(c, o); assertEquals("BALANCE", bal.path("stage").asText()); assertEquals(5000, bal.path("amountTwd").asLong());
        o = simulate(c, bal).path("order");
        assertEquals("PAID", o.path("balanceStatus").asText()); assertEquals(2, batchNow(a, b).path("paidQuantity").asInt(), "尾款不重複計名額");
        assertEquals(200, allocateStatus(a, o, b));
        // 時程異動通知只送給尚未到貨的訂單；已分配的訂單不受影響
        var d = customer(); var late = pay(d, preorder(d, b, 1, PRICE));
        assertEquals("DUE", late.path("balanceStatus").asText(), "通知尾款後才付訂金的訂單直接進入待付尾款");
        assertEquals(1, outbox(late, "PREORDER_BALANCE_DUE"));
    }

    @Test void delayRecordsHistoryUpdatesOpenOrdersAndNotifiesWithoutStaffNotes() throws Exception {
        var a = admin(); var f = fixture(a, 0, PRICE); var b = batch(a, f, 10, PRICE, true);
        var c = customer(); var pending = preorder(c, b, 1, PRICE); var paid = pay(c, preorder(c, b, 1, PRICE));
        var cancelled = preorder(c, b, 1, PRICE);
        ok(c, "POST", "/customer/preorders/" + cancelled.path("id").asText() + "/cancel", Map.of("requestId", uuid(), "expectedVersion", cancelled.path("version").asInt()), 200);
        var current = batchNow(a, b);
        LocalDate from = LocalDate.parse(current.path("arrivalFrom").asText()).plusDays(20), to = LocalDate.parse(current.path("arrivalTo").asText()).plusDays(30);
        var body = new HashMap<String, Object>(Map.of("requestId", uuid(), "expectedVersion", current.path("version").asInt(), "arrivalFrom", from.toString(), "arrivalTo", to.toString(),
                "publicReason", "原廠延後出貨", "note", "內部：工廠排程 <secret>"));
        var r = data(ok(a, "POST", "/admin/preorders/" + b.path("id").asText() + "/schedule", body, 201));
        assertEquals("DELAY", r.path("event").path("action").asText()); assertEquals(2, r.path("event").path("affectedOrders").asInt());
        assertEquals(to.toString(), r.path("current").path("arrivalTo").asText());
        assertTrue(data(ok(a, "POST", "/admin/preorders/" + b.path("id").asText() + "/schedule", body, 200)).path("replayed").asBoolean());
        for (var o : List.of(pending, paid)) {
            var v = order(c, o);
            assertEquals(to.toString(), v.path("arrivalTo").asText());
            assertEquals(1, v.path("schedule").size()); var n = v.path("schedule").get(0);
            assertEquals("原廠延後出貨", n.path("publicReason").asText()); assertEquals(current.path("arrivalTo").asText(), n.path("arrivalToBefore").asText());
            assertFalse(v.toString().contains("<secret>")); assertFalse(n.has("note")); assertFalse(n.has("actorUsername"));
            assertEquals(1, outbox(o, "PREORDER_DELAYED"));
        }
        assertEquals(0, outbox(cancelled, "PREORDER_DELAYED"), "已結束的訂單不改、不通知");
        var detail = data(ok(a, "GET", "/admin/preorders/" + b.path("id").asText(), null, 200));
        assertEquals(1, detail.path("schedules").size()); assertTrue(detail.path("schedules").get(0).path("note").asText().contains("<secret>"));
        // 提前視為調整；不變、早於截單、草稿都拒絕；員工無權限 403
        var again = batchNow(a, b);
        var earlier = new HashMap<String, Object>(Map.of("requestId", uuid(), "expectedVersion", again.path("version").asInt(), "arrivalFrom", from.minusDays(5).toString(), "arrivalTo", to.minusDays(5).toString(), "publicReason", "提早到貨", "note", "內部"));
        assertEquals("ADJUST", data(ok(a, "POST", "/admin/preorders/" + b.path("id").asText() + "/schedule", earlier, 201)).path("event").path("action").asText());
        assertEquals(1, outbox(pending, "PREORDER_RESCHEDULED"));
        var same = new HashMap<>(earlier); same.put("requestId", uuid()); same.put("expectedVersion", batchNow(a, b).path("version").asInt());
        assertEquals(409, call(a, "POST", "/admin/preorders/" + b.path("id").asText() + "/schedule", same).getResponse().getStatus());
        var tooEarly = new HashMap<>(same); tooEarly.put("arrivalFrom", LocalDate.now().toString()); tooEarly.put("arrivalTo", to.toString());
        assertEquals(400, call(a, "POST", "/admin/preorders/" + b.path("id").asText() + "/schedule", tooEarly).getResponse().getStatus());
        var draft = batch(a, f, 5, PRICE, false);
        var dBody = new HashMap<String, Object>(Map.of("requestId", uuid(), "expectedVersion", draft.path("version").asInt(), "arrivalFrom", from.toString(), "arrivalTo", to.toString(), "publicReason", "x", "note", "x"));
        assertEquals(409, call(a, "POST", "/admin/preorders/" + draft.path("id").asText() + "/schedule", dBody).getResponse().getStatus());
        int denied = call(c, "POST", "/admin/preorders/" + b.path("id").asText() + "/schedule", same).getResponse().getStatus(); assertTrue(denied == 401 || denied == 403, "顧客不能操作員工端");
    }

    @Test void refundsKeepEveryPaymentAndAuditDepositAndBalanceSeparately() throws Exception {
        var a = admin(); var f = fixture(a, 0, PRICE); var b = depositBatch(a, f); var c = customer();
        // 只付訂金就退款：一筆退款稽核、名額釋放
        var onlyDeposit = pay(c, preorder(c, b, 1, PRICE));
        var s = staff(a, onlyDeposit);
        assertTrue(s.path("canRefund").asBoolean());
        ok(a, "POST", "/admin/preorder-orders/" + onlyDeposit.path("id").asText() + "/mock-refund", Map.of("requestId", uuid(), "expectedOrderVersion", s.path("order").path("version").asInt(), "note", "顧客取消"), 200);
        var refunded = order(c, onlyDeposit);
        assertEquals("REFUNDED", refunded.path("status").asText()); assertEquals(DEPOSIT, refunded.path("refund").path("amountTwd").asLong());
        // 訂金＋尾款都付了（尚未分配）再退款：兩筆退款稽核、金額合計為全額、原付款都保留
        var o = pay(c, preorder(c, b, 1, PRICE)); receive(a, b, 1);
        ok(a, "POST", "/admin/preorders/" + b.path("id").asText() + "/balance-call", balanceBody(batchNow(a, b), "到貨"), 201);
        o = pay(c, order(c, o)); assertEquals("PAID", o.path("balanceStatus").asText());
        s = staff(a, o); int before = s.path("order").path("version").asInt();
        var refundBody = Map.<String, Object>of("requestId", uuid(), "expectedOrderVersion", before, "note", "延期太久");
        ok(a, "POST", "/admin/preorder-orders/" + o.path("id").asText() + "/mock-refund", refundBody, 200);
        var after = order(c, o);
        assertEquals(PRICE, after.path("refund").path("amountTwd").asLong()); assertEquals(before + 2, after.path("version").asInt());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM preorder_refund_event WHERE order_id=?", Integer.class, o.path("id").asText()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM preorder_mock_payment WHERE order_id=? AND status='SUCCEEDED'", Integer.class, o.path("id").asText()));
        assertTrue(data(ok(a, "POST", "/admin/preorder-orders/" + o.path("id").asText() + "/mock-refund", refundBody, 200)).path("replayed").asBoolean());
        assertEquals(0, batchNow(a, b).path("paidQuantity").asInt());
        // 退款後才到的尾款成功通知：待核對，不復活、不占名額
        assertFalse(data(ok(c, "GET", "/customer/preorders/" + o.path("id").asText() + "/mock-payments", null, 200)).path("canStart").asBoolean());
    }

    @Test void termsValidationFullBatchesUnchangedAndQuoteSplitsPayNow() throws Exception {
        var a = admin(); var f = fixture(a, 0, PRICE);
        assertEquals(400, call(a, "POST", "/admin/preorders", batchBody(f, "DEPOSIT", PRICE, 7)).getResponse().getStatus(), "訂金需小於單價");
        assertEquals(400, call(a, "POST", "/admin/preorders", batchBody(f, "DEPOSIT", 100, null)).getResponse().getStatus(), "需尾款天數");
        assertEquals(400, call(a, "POST", "/admin/preorders", batchBody(f, "FULL", 100, null)).getResponse().getStatus(), "全額不可設訂金");
        assertEquals(400, call(a, "POST", "/admin/preorders", batchBody(f, "HALF", null, null)).getResponse().getStatus());
        var full = batch(a, f, 5, PRICE, true); assertEquals("FULL", full.path("paymentMode").asText());
        var c = customer(); var o = preorder(c, full, 1, PRICE);
        assertEquals("NONE", o.path("balanceStatus").asText()); assertEquals(PRICE, o.path("payNowTwd").asLong());
        var p = start(c, o); assertEquals("FULL", p.path("stage").asText()); assertEquals(PRICE, p.path("amountTwd").asLong());
        // 統一結帳報價：訂金批次現在付訂金、之後付尾款
        var dep = depositBatch(a, f);
        ok(c, "PUT", "/customer/cart/preorders/" + dep.path("id").asText(), Map.of("expectedVersion", cart(c).path("version").asInt(), "quantity", 2), 200);
        var q = data(ok(c, "GET", "/customer/checkout/quote", null, 200));
        var g = q.path("groups").get(0);
        assertEquals("DEPOSIT", g.path("paymentMode").asText()); assertEquals(1000, g.path("payNowTwd").asLong()); assertEquals(5000, g.path("payLaterTwd").asLong());
        assertEquals(1000, q.path("payNowTwd").asLong());
        var placed = data(ok(c, "POST", "/customer/checkout", Map.of("requestId", uuid(), "cartVersion", q.path("cartVersion").asInt(), "addressId", newAddress(c), "addressVersion", 1, "expectedTotalTwd", q.path("totalTwd").asLong()), 200));
        var child = order(c, placed.path("orders").get(0));
        assertEquals("DEPOSIT", child.path("paymentMode").asText()); assertEquals(1000, child.path("payNowTwd").asLong());
    }
}
