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

/** Commerce V2 Phase 6：政策頁草稿／審核／發布（四眼）、客服單（本人、內部備註不外露）、測試物流追蹤與通知。 */
@SpringBootTest(properties = "shop.orders.expiry-delay-ms=600000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "SHOP_MYSQL_INTEGRATION", matches = "true")
class SupportIntegrationTest extends ShopIntegrationSupport {
    private Client staff() throws Exception {
        var c = guest(); String device = uuid();
        var r = StaffLogin.complete(mvc, () -> mvc.perform(post("/figura/api/admin/login").contextPath("/figura").servletPath("/api/admin/login").cookie(c.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN", c.csrf())
                .param("username", "shop_staff").param("password", System.getenv("DEMO_STAFF_PASSWORD")).param("deviceId", device)).andReturn());
        assertEquals(200, r.getResponse().getStatus()); String id = data(r).path("id").asText(); sessions.add("shop:session:" + id + ":" + device);
        return new Client(c.csrf(), List.of(c.cookies().get(0), cookie(r, "accessToken"), cookie(r, "refreshToken")), id);
    }
    private JsonNode page(Client a, String slug) throws Exception { return data(ok(a, "GET", "/admin/site-pages/" + slug, null, 200)).path("page"); }

    @Test void policyPagesNeedReviewByAnotherStaff() throws Exception {
        var pub = data(ok(guest(), "GET", "/site-pages/privacy", null, 200));
        if (!pub.path("published").asBoolean()) assertEquals("隱私權政策", pub.path("title").asText(), "未發布不投影草稿標題");
        if (!pub.path("published").asBoolean()) assertTrue(pub.path("body").isNull(), "未發布不回草稿");
        var a = admin(); var s = staff();
        String body = "測試內容 " + uuid();
        int v = page(a, "faq").path("version").asInt();
        ok(s, "PUT", "/admin/site-pages/faq", Map.of("requestId", uuid(), "expectedVersion", v, "title", "常見問題", "body", body, "note", "撰寫草稿"), 200);
        assertEquals(403, call(s, "POST", "/admin/site-pages/faq/publish", Map.of("requestId", uuid(), "expectedVersion", v + 1, "note", "x")).getResponse().getStatus(), "員工沒有發布權限");
        var submitted = data(ok(s, "POST", "/admin/site-pages/faq/submit", Map.of("requestId", uuid(), "expectedVersion", v + 1, "note", "送審"), 200)).path("current").path("page");
        assertEquals("IN_REVIEW", submitted.path("status").asText());
        assertNotEquals(body, data(ok(guest(), "GET", "/site-pages/faq", null, 200)).path("body").asText(""), "送審中不公開");
        var published = data(ok(a, "POST", "/admin/site-pages/faq/publish", Map.of("requestId", uuid(), "expectedVersion", v + 2, "note", "審核通過"), 200)).path("current").path("page");
        assertEquals("PUBLISHED", published.path("status").asText());
        var live = data(ok(guest(), "GET", "/site-pages/faq", null, 200));
        assertTrue(live.path("published").asBoolean()); assertEquals(body, live.path("body").asText());
        // 店長自己編輯就不能自己發布
        String again = "店長改的 " + uuid();
        ok(a, "PUT", "/admin/site-pages/faq", Map.of("requestId", uuid(), "expectedVersion", v + 3, "title", "常見問題", "body", again, "note", "修正"), 200);
        assertEquals(body, data(ok(guest(), "GET", "/site-pages/faq", null, 200)).path("body").asText(), "編輯不影響已發布版本");
        ok(a, "POST", "/admin/site-pages/faq/submit", Map.of("requestId", uuid(), "expectedVersion", v + 4, "note", "送審"), 200);
        var self = call(a, "POST", "/admin/site-pages/faq/publish", Map.of("requestId", uuid(), "expectedVersion", v + 5, "note", "自己發布"));
        assertEquals(409, self.getResponse().getStatus(), text(self));
        ok(a, "POST", "/admin/site-pages/faq/reject", Map.of("requestId", uuid(), "expectedVersion", v + 5, "note", "退回"), 200);
        // 空白內容不能送審；舊版本 409
        int cv = page(a, "contact").path("version").asInt();
        if (page(a, "contact").path("draftBody").asText().isBlank())
            assertEquals(409, call(a, "POST", "/admin/site-pages/contact/submit", Map.of("requestId", uuid(), "expectedVersion", cv, "note", "送審")).getResponse().getStatus());
        assertEquals(409, call(s, "PUT", "/admin/site-pages/faq", Map.of("requestId", uuid(), "expectedVersion", v, "title", "t", "body", "b", "note", "舊版")).getResponse().getStatus());
    }

    @Test void ticketsArePrivateAndInternalNotesHidden() throws Exception {
        var a = admin(); var c = customer(); var other = customer(); var f = fixture(a, 3);
        String order = stockOrder(c, f, 1, 1280).path("id").asText();
        String request = uuid();
        var body = Map.of("requestId", request, "category", "order", "subject", "想確認出貨時間", "body", "請問什麼時候出貨？", "orderId", order);
        var t = data(ok(c, "POST", "/customer/support-tickets", body, 200)).path("current");
        String id = t.path("ticket").path("id").asText();
        assertEquals("WAITING_STAFF", t.path("ticket").path("status").asText()); assertEquals("ORDER", t.path("ticket").path("category").asText());
        assertTrue(data(ok(c, "POST", "/customer/support-tickets", body, 200)).path("replayed").asBoolean());
        assertEquals(404, call(other, "POST", "/customer/support-tickets", Map.of("requestId", uuid(), "category", "ORDER", "subject", "x", "body", "y", "orderId", order)).getResponse().getStatus(), "只能關聯本人訂單");
        assertEquals(404, call(other, "GET", "/customer/support-tickets/" + id, null).getResponse().getStatus());
        // 內部備註與回覆
        ok(a, "POST", "/admin/support-tickets/" + id + "/messages", Map.of("requestId", uuid(), "body", "內部：先查庫存", "internal", true), 200);
        var replied = data(ok(a, "POST", "/admin/support-tickets/" + id + "/messages", Map.of("requestId", uuid(), "body", "您好，預計三天內出貨。"), 200)).path("current");
        assertEquals("WAITING_CUSTOMER", replied.path("ticket").path("status").asText()); assertEquals(3, replied.path("messages").size());
        var mine = data(ok(c, "GET", "/customer/support-tickets/" + id, null, 200));
        assertEquals(2, mine.path("messages").size(), "顧客看不到內部備註");
        for (var m : mine.path("messages")) assertFalse(m.path("body").asText().startsWith("內部"));
        assertEquals(400, call(c, "POST", "/customer/support-tickets/" + id + "/messages", Map.of("requestId", uuid(), "body", "x", "internal", true)).getResponse().getStatus());
        assertEquals("WAITING_STAFF", data(ok(c, "POST", "/customer/support-tickets/" + id + "/messages", Map.of("requestId", uuid(), "body", "好的謝謝"), 200)).path("current").path("ticket").path("status").asText());
        // 指派、結案；結案後顧客不能再回覆
        int v = data(ok(a, "GET", "/admin/support-tickets/" + id, null, 200)).path("ticket").path("version").asInt();
        ok(a, "POST", "/admin/support-tickets/" + id + "/assign", Map.of("requestId", uuid(), "expectedVersion", v, "assigneeId", Integer.parseInt(a.id()), "note", "我來處理"), 200);
        ok(a, "POST", "/admin/support-tickets/" + id + "/status", Map.of("requestId", uuid(), "expectedVersion", v + 1, "status", "CLOSED", "note", "已回覆"), 200);
        assertEquals(409, call(c, "POST", "/customer/support-tickets/" + id + "/messages", Map.of("requestId", uuid(), "body", "再問一下")).getResponse().getStatus());
        assertEquals(409, call(a, "POST", "/admin/support-tickets/" + id + "/status", Map.of("requestId", uuid(), "expectedVersion", v, "status", "RESOLVED", "note", "舊版")).getResponse().getStatus());
        assertTrue(data(ok(a, "GET", "/admin/support-tickets?status=CLOSED&pageSize=50", null, 200)).path("total").asInt() >= 1);
        assertEquals(1, data(ok(c, "GET", "/customer/support-tickets", null, 200)).path("total").asInt());
    }

    @Test void testCarrierTrackingTimelineAndNotice() throws Exception {
        if (!"true".equals(System.getenv("MOCK_FULFILLMENT_ENABLED")) || !"true".equals(System.getenv("MOCK_PAYMENTS_ENABLED"))) return;
        var a = admin(); var c = customer(); var f = fixture(a, 3, 800); String address = newAddress(c);
        ok(c, "PUT", f.cart(), Map.of("expectedVersion", cart(c).path("version").asInt(), "quantity", 1), 200);
        var q = data(ok(c, "GET", "/customer/checkout/quote?shippingMethod=CVS&deliveryStrategy=SPLIT", null, 200));
        String order = data(ok(c, "POST", "/customer/checkout", Map.of("requestId", uuid(), "cartVersion", q.path("cartVersion").asInt(), "addressId", address, "addressVersion", 1,
                "expectedTotalTwd", q.path("totalTwd").asLong(), "shippingMethod", "CVS", "deliveryStrategy", "SPLIT", "pickupStoreId", "TEST-711-001"), 200)).path("orders").get(0).path("id").asText();
        var o = data(ok(c, "GET", "/customer/orders/" + order, null, 200));
        var p = data(ok(c, "POST", "/customer/orders/" + order + "/mock-payments", Map.of("requestId", uuid(), "expectedOrderVersion", o.path("version").asInt()), 200)).path("payment");
        ok(c, "POST", "/customer/mock-payments/" + p.path("id").asText() + "/simulate", Map.of("eventId", uuid(), "outcome", "SUCCESS"), 200);
        String sid = data(ok(c, "GET", "/customer/orders/" + order, null, 200)).path("shipment").path("id").asText();
        assertEquals(409, call(a, "POST", "/admin/shipments/" + sid + "/tracking", Map.of("requestId", uuid(), "code", "PICKED_UP")).getResponse().getStatus(), "未出貨沒有追蹤");
        var d = data(ok(a, "GET", "/admin/shipments/" + sid, null, 200));
        ok(a, "POST", "/admin/shipments/" + sid + "/ship", Map.of("requestId", uuid(), "expectedVersion", d.path("shipment").path("version").asInt(), "note", "出貨"), 201);
        ok(a, "POST", "/admin/shipments/" + sid + "/tracking", Map.of("requestId", uuid(), "code", "PICKED_UP"), 200);
        assertEquals(409, call(a, "POST", "/admin/shipments/" + sid + "/tracking", Map.of("requestId", uuid(), "code", "OUT_FOR_DELIVERY")).getResponse().getStatus(), "超商沒有配送中");
        var arrive = Map.of("requestId", uuid(), "code", "ARRIVED_STORE");
        ok(a, "POST", "/admin/shipments/" + sid + "/tracking", arrive, 200);
        var tl = data(ok(a, "POST", "/admin/shipments/" + sid + "/tracking", arrive, 200));
        assertEquals(2, tl.path("events").size(), "同 requestId 不重複");
        var mine = data(ok(c, "GET", "/customer/shipments/" + sid + "/tracking", null, 200));
        assertEquals("ARRIVED_STORE", mine.path("events").get(1).path("code").asText()); assertTrue(mine.path("testCarrier").asBoolean());
        assertEquals(404, call(customer(), "GET", "/customer/shipments/" + sid + "/tracking", null).getResponse().getStatus());
        boolean notified = false; for (var n : data(ok(c, "GET", "/customer/notifications", null, 200)).path("items")) notified |= n.path("kind").asText().equals("SHIPMENT_ARRIVED_STORE");
        assertTrue(notified);
    }
}
