package dev.jackteng.figura;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * 購物相關整合測試共用：真實 HTTP 安全鏈、隔離 MySQL／Redis，建立合成顧客、商品、預購批次與訂單。
 * 子類別自行加上 @SpringBootTest／@ActiveProfiles("demo")／@EnabledIfEnvironmentVariable。
 */
abstract class ShopIntegrationSupport {
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected StringRedisTemplate redis;
    @Autowired protected dev.jackteng.figura.service.OrderService orderService;
    @Autowired protected dev.jackteng.figura.service.PreorderOrderService preorderService;
    protected final Set<String> customers = ConcurrentHashMap.newKeySet(), products = ConcurrentHashMap.newKeySet(), sessions = ConcurrentHashMap.newKeySet();
    record Client(String csrf, List<Cookie> cookies, String id) {}
    record Fixture(String product, String sku) { String cart() { return "/customer/cart/items/" + sku; } }

    protected JsonNode data(MvcResult r) throws Exception { return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data"); }
    protected String text(MvcResult r) throws Exception { return r.getResponse().getContentAsString(StandardCharsets.UTF_8); }
    protected Cookie cookie(MvcResult r, String name) {
        var h = r.getResponse().getHeaders("Set-Cookie").stream().filter(v -> v.startsWith(name + "=")).findFirst().orElseThrow();
        return new Cookie(name, h.split(";", 2)[0].substring(name.length() + 1));
    }
    protected MvcResult call(Client c, String method, String path, Object body) throws Exception {
        var b = request(org.springframework.http.HttpMethod.valueOf(method), "/figura/api" + path).contextPath("/figura").servletPath("/api" + path.split("\\?", 2)[0]);
        if (c != null) { b.cookie(c.cookies().toArray(Cookie[]::new)); if (!method.equals("GET")) b.header("X-XSRF-TOKEN", c.csrf()); }
        if (body != null) b.contentType("application/json").content(json.writeValueAsBytes(body));
        return mvc.perform(b).andReturn();
    }
    protected MvcResult ok(Client c, String method, String path, Object body, int status) throws Exception {
        var r = call(c, method, path, body); assertEquals(status, r.getResponse().getStatus(), method + " " + path + " → " + text(r)); return r;
    }
    protected Client guest() throws Exception { var r = call(null, "GET", "/auth/csrf", null); return new Client(data(r).path("token").asText(), List.of(cookie(r, "XSRF-TOKEN")), ""); }
    protected Client customer() throws Exception {
        var c = guest(); String name = "shop_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), password = "test-shop-password-2026", device = UUID.randomUUID().toString();
        var r = ok(c, "POST", "/customer/auth/register", Map.of("email", name + "@example.test", "phone", TestPhones.next(), "password", password), 201);
        String id = data(r).path("id").asText(); customers.add(id);
        r = ok(c, "POST", "/customer/auth/login", Map.of("username", name + "@example.test", "password", password, "deviceId", device, "rememberMe", false), 200);
        sessions.add("shop:customer:session:" + id + ":" + device);
        return new Client(c.csrf(), List.of(c.cookies().get(0), cookie(r, "customerAccessToken"), cookie(r, "customerRefreshToken")), id);
    }
    protected Client admin() throws Exception {
        var c = guest(); String device = UUID.randomUUID().toString();
        var r = mvc.perform(post("/figura/api/admin/login").contextPath("/figura").servletPath("/api/admin/login").cookie(c.cookies().toArray(Cookie[]::new)).header("X-XSRF-TOKEN", c.csrf())
                .param("username", "shop_admin").param("password", System.getenv("DEMO_ADMIN_PASSWORD")).param("deviceId", device)).andReturn();
        assertEquals(200, r.getResponse().getStatus()); String id = data(r).path("id").asText(); sessions.add("shop:session:" + id + ":" + device);
        return new Client(c.csrf(), List.of(c.cookies().get(0), cookie(r, "accessToken"), cookie(r, "refreshToken")), id);
    }
    protected Fixture fixture(Client admin, int stock, int price) throws Exception {
        var r = ok(admin, "POST", "/admin/products", Map.of("title", "Shop " + UUID.randomUUID(), "brand", "ELPSY", "category", "公仔", "description", "合成商品", "expectedVersion", 0,
                "skus", List.of(Map.of("code", "SHOP-" + UUID.randomUUID(), "label", "標準款", "priceTwd", price, "enabled", true))), 201);
        var p = data(r); var f = new Fixture(p.path("id").asText(), p.path("skus").get(0).path("id").asText()); products.add(f.product());
        if (stock > 0) ok(admin, "POST", "/admin/products/" + f.product() + "/skus/" + f.sku() + "/inventory/adjustments",
                Map.of("requestId", uuid(), "expectedVersion", 0, "delta", stock, "reason", "RESTOCK", "note", "合成測試入庫"), 201);
        ok(admin, "PATCH", "/admin/products/" + f.product() + "/publication", Map.of("expectedVersion", 0, "published", true), 200);
        return f;
    }
    protected Fixture fixture(Client admin, int stock) throws Exception { return fixture(admin, stock, 1280); }
    protected JsonNode cart(Client c) throws Exception { return data(ok(c, "GET", "/customer/cart", null, 200)); }
    protected Map<String, Object> address(int version) {
        return new HashMap<>(Map.of("expectedVersion", version, "recipientName", "合成收件人", "phone", "0912345678", "postalCode", "100", "city", "測試市", "district", "測試區", "street", "合成路 1 號"));
    }
    protected String newAddress(Client c) throws Exception { String id = uuid(); ok(c, "PUT", "/customer/addresses/" + id, address(0), 200); return id; }
    protected String uuid() { return UUID.randomUUID().toString(); }
    /** 現貨下單（直接購買單一款式）。 */
    protected JsonNode stockOrder(Client c, Fixture f, int qty, int price) throws Exception {
        var r = ok(c, "POST", "/customer/orders/direct", Map.of("requestId", uuid(), "productId", f.product(), "skuId", f.sku(), "quantity", qty, "addressId", newAddress(c), "addressVersion", 1, "expectedSubtotalTwd", (long) price * qty), 200);
        return data(r).path("order");
    }
    protected JsonNode batch(Client a, Fixture f, int capacity, int price, boolean open) throws Exception {
        var now = java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(8)).withNano(0);
        var body = Map.ofEntries(Map.entry("productId", f.product()), Map.entry("skuId", f.sku()), Map.entry("name", "Preorder " + uuid()), Map.entry("description", "合成預購"),
                Map.entry("priceTwd", price), Map.entry("capacity", capacity), Map.entry("deadlineAt", now.plusDays(1).toString()), Map.entry("arrivalFrom", now.plusDays(30).toLocalDate().toString()),
                Map.entry("arrivalTo", now.plusDays(40).toLocalDate().toString()), Map.entry("expectedVersion", 0), Map.entry("requestId", uuid()), Map.entry("note", "合成批次"));
        var b = data(ok(a, "POST", "/admin/preorders", body, 201)).path("current");
        if (!open) return b;
        return data(ok(a, "POST", "/admin/preorders/" + b.path("id").asText() + "/open", Map.of("expectedVersion", b.path("version").asInt(), "requestId", uuid(), "note", "合成開放"), 201)).path("current");
    }
    protected JsonNode preorder(Client c, JsonNode b, int qty, int price) throws Exception {
        var r = ok(c, "POST", "/customer/preorders", Map.of("requestId", uuid(), "batchId", b.path("id").asText(), "quantity", qty, "addressId", newAddress(c), "addressVersion", 1, "expectedSubtotalTwd", (long) price * qty), 201);
        return data(r).path("order");
    }
    protected void makePreorderDue(String id) { jdbc.update("UPDATE preorder_order SET created_at=CURRENT_TIMESTAMP(6)-INTERVAL 29 MINUTE,expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", id); }

    @AfterEach void shopCleanup() {
        for (String customer : customers) {
            for (String order : jdbc.queryForList("SELECT id FROM preorder_order WHERE customer_id=? AND status='PENDING'", String.class, customer)) { makePreorderDue(order); preorderService.expire(customer, order); }
            for (String order : jdbc.queryForList("SELECT id FROM customer_order WHERE customer_id=? AND status='PENDING_PAYMENT'", String.class, customer)) {
                jdbc.update("UPDATE customer_order SET expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", order); orderService.expire(customer, order);
            }
            jdbc.update("UPDATE customer SET enabled=FALSE WHERE id=?", customer);
        }
        for (String id : products) jdbc.update("UPDATE catalog_product SET status='ARCHIVED' WHERE id=?", id);
        if (!sessions.isEmpty()) redis.delete(sessions);
    }
}
