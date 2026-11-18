package dev.jackteng.figura;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 統一訂單中心：真實下單／付款／出貨流程產生各種狀態，驗證篩選、排序、全域分頁、各階段筆數與本人隔離。 */
@SpringBootTest(properties = {"shop.orders.expiry-delay-ms=600000", "MOCK_PAYMENTS_ENABLED=true", "MOCK_FULFILLMENT_ENABLED=true"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "SHOP_MYSQL_INTEGRATION", matches = "true")
class OrderCenterIntegrationTest extends ShopIntegrationSupport {
    @Autowired dev.jackteng.figura.service.MockPaymentGateway gateway;

    private MvcResult signed(String path, Map<String, Object> body) throws Exception {
        String raw = json.writeValueAsString(body), ts = Long.toString(java.time.Instant.now().getEpochSecond());
        return mvc.perform(post("/figura/api" + path).contextPath("/figura").servletPath("/api" + path)
                .header("X-Mock-Timestamp", ts).header("X-Mock-Signature", gateway.sign(ts, raw)).contentType("application/json").content(raw)).andReturn();
    }
    private void pay(Client c, String base, String callback, JsonNode order) throws Exception {
        var p = data(ok(c, "POST", base + order.path("id").asText() + "/mock-payments", Map.of("requestId", uuid(), "expectedOrderVersion", order.path("version").asInt()), 200)).path("payment");
        assertEquals(200, signed(callback, new HashMap<>(Map.of("eventId", uuid(), "paymentId", p.path("id").asText(), "transactionId", "mock_" + p.path("id").asText(),
                "amountTwd", p.path("amountTwd").asLong(), "currency", "TWD", "outcome", "SUCCESS"))).getResponse().getStatus());
    }
    private JsonNode center(Client c, String query) throws Exception { return data(ok(c, "GET", "/customer/order-center" + query, null, 200)); }
    private List<String> ids(JsonNode page) { var out = new ArrayList<String>(); page.path("items").forEach(i -> out.add(i.path("id").asText())); return out; }

    @Test void mergesStockAndPreorderWithStagesFiltersSortAndGlobalPaging() throws Exception {
        var a = admin(); var f = fixture(a, 10, 500); var g = fixture(a, 0, 900); var b = batch(a, g, 10, 900, true);
        var c = customer(); var other = customer();
        var pendingStock = stockOrder(c, f, 1, 500);                              // 待付款，500
        var paidStock = stockOrder(c, f, 2, 500); pay(c, "/customer/orders/", "/payments/mock/callback", paidStock);   // 待出貨，1000
        var shipped = stockOrder(c, f, 3, 500); pay(c, "/customer/orders/", "/payments/mock/callback", shipped);        // 已出貨，1500
        var s = data(ok(a, "GET", "/admin/orders/" + shipped.path("id").asText(), null, 200)).path("order");
        ok(a, "POST", "/admin/orders/" + shipped.path("id").asText() + "/mock-shipment", Map.of("requestId", uuid(), "expectedOrderVersion", s.path("version").asInt(), "note", "合成出貨"), 200);
        var cancelled = stockOrder(c, f, 1, 500);
        ok(c, "POST", "/customer/orders/" + cancelled.path("id").asText() + "/cancel", Map.of("expectedVersion", cancelled.path("version").asInt()), 200);
        var pendingPre = preorder(c, b, 1, 900);                                  // 預購待付款，900
        var paidPre = preorder(c, b, 2, 900); pay(c, "/customer/preorders/", "/preorder-payments/mock/callback", paidPre); // 等待到貨，1800

        var all = center(c, "?pageSize=50");
        assertEquals(6, all.path("total").asInt());
        Map<String, String> stage = new HashMap<>(); all.path("items").forEach(i -> stage.put(i.path("id").asText(), i.path("stage").asText()));
        assertEquals("PENDING_PAYMENT", stage.get(pendingStock.path("id").asText()));
        assertEquals("TO_SHIP", stage.get(paidStock.path("id").asText()));
        assertEquals("SHIPPED", stage.get(shipped.path("id").asText()));
        assertEquals("CANCELLED", stage.get(cancelled.path("id").asText()));
        assertEquals("PENDING_PAYMENT", stage.get(pendingPre.path("id").asText()));
        assertEquals("WAITING_ARRIVAL", stage.get(paidPre.path("id").asText()));
        assertEquals(2, all.path("counts").path("PENDING_PAYMENT").asInt());
        assertEquals(6, all.path("counts").path("ALL").asInt());
        // 摘要欄位
        var pre = all.path("items").findParents("id").stream().filter(i -> i.path("id").asText().equals(paidPre.path("id").asText())).findFirst().orElseThrow();
        assertEquals("PREORDER", pre.path("kind").asText()); assertEquals(1800, pre.path("totalTwd").asLong()); assertEquals(2, pre.path("quantity").asInt());
        assertEquals(g.product(), pre.path("productId").asText()); assertFalse(pre.path("arrivalFrom").isNull());

        // 篩選
        assertEquals(2, center(c, "?kind=PREORDER").path("total").asInt());
        assertEquals(4, center(c, "?kind=STOCK").path("total").asInt());
        assertEquals(Set.of(pendingStock.path("id").asText(), pendingPre.path("id").asText()), new HashSet<>(ids(center(c, "?stage=PENDING_PAYMENT"))));
        assertEquals(List.of(pendingPre.path("id").asText()), ids(center(c, "?kind=PREORDER&stage=PENDING_PAYMENT")));
        assertEquals(1, center(c, "?kind=PREORDER").path("counts").path("PENDING_PAYMENT").asInt());

        // 排序：金額高到低 1800、1500、1000、900、500、500
        var byAmount = new ArrayList<Long>(); center(c, "?sort=AMOUNT_DESC&pageSize=50").path("items").forEach(i -> byAmount.add(i.path("totalTwd").asLong()));
        assertEquals(List.of(1800L, 1500L, 1000L, 900L, 500L, 500L), byAmount);
        // 全域分頁：每頁 4 筆，兩頁剛好涵蓋全部、不重複，且順序與單頁查詢一致
        var p1 = center(c, "?sort=AMOUNT_DESC&pageSize=4&page=1"); var p2 = center(c, "?sort=AMOUNT_DESC&pageSize=4&page=2");
        var paged = new ArrayList<>(ids(p1)); paged.addAll(ids(p2));
        assertEquals(ids(center(c, "?sort=AMOUNT_DESC&pageSize=50")), paged);
        assertEquals(2, p2.path("items").size()); assertEquals(6, p2.path("total").asInt());
        // 最新／最舊互為反序
        var newest = ids(center(c, "?pageSize=50")); var oldest = ids(center(c, "?sort=OLDEST&pageSize=50")); Collections.reverse(oldest);
        assertEquals(newest, oldest);

        // 本人隔離：別人看不到；網站與參數錯誤
        assertEquals(0, center(other, "").path("total").asInt());
        assertEquals(401, call(guest(), "GET", "/customer/order-center", null).getResponse().getStatus());
        assertEquals(400, call(c, "GET", "/customer/order-center?stage=NOPE", null).getResponse().getStatus());
        assertEquals(400, call(c, "GET", "/customer/order-center?pageSize=500", null).getResponse().getStatus());
        assertEquals(400, call(c, "GET", "/customer/order-center?sort=created_at", null).getResponse().getStatus());
    }

    @Test void appBearerSeesTheSameOwnOrders() throws Exception {
        var a = admin(); var f = fixture(a, 5, 700); var c = customer();
        var o = stockOrder(c, f, 1, 700);
        String email = jdbc.queryForObject("SELECT email FROM customer WHERE id=?", String.class, c.id());
        var login = mvc.perform(post("/figura/api/mobile/customer/auth/login").contextPath("/figura").servletPath("/api/mobile/customer/auth/login").contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("username", email, "password", "test-shop-password-2026", "deviceId", uuid(), "rememberMe", false)))).andReturn();
        assertEquals(200, login.getResponse().getStatus());
        String token = data(login).path("accessToken").asText(); sessions.add("shop:customer:mobile:session:" + c.id() + ":*");
        var r = mvc.perform(get("/figura/api/mobile/customer/order-center").contextPath("/figura").servletPath("/api/mobile/customer/order-center").header("Authorization", "Bearer " + token)).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        assertEquals(List.of(o.path("id").asText()), ids(data(r)));
        // 網站 Cookie 不能用在 App 路徑
        assertEquals(401, call(c, "GET", "/mobile/customer/order-center", null).getResponse().getStatus());
    }
}
