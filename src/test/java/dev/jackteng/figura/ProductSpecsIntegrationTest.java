package dev.jackteng.figura;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Commerce V2 Phase 1：公仔規格欄位、白名單驗證、舊客戶端相容、AI 概念商品材質不外露、可售狀態。 */
@SpringBootTest(properties = "shop.orders.expiry-delay-ms=600000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "SHOP_MYSQL_INTEGRATION", matches = "true")
class ProductSpecsIntegrationTest extends ShopIntegrationSupport {
    private Map<String, Object> product(Map<String, Object> specs) {
        var body = new HashMap<String, Object>(Map.of("title", "規格測試 " + uuid(), "brand", "ELPSY", "category", "公仔", "description", "合成規格商品", "expectedVersion", 0,
                "skus", List.of(Map.of("code", "SPEC-" + uuid(), "label", "標準款", "priceTwd", 3200, "enabled", true))));
        if (specs != null) body.put("specs", specs);
        return body;
    }
    private Map<String, Object> fullSpecs() {
        var s = new HashMap<String, Object>();
        s.put("characterName", "月梨"); s.put("manufacturer", "合成製作所"); s.put("scale", "1/7"); s.put("heightMm", 245);
        s.put("dimensions", "寬 120 × 深 110 mm"); s.put("materials", List.of("pvc", "ABS")); s.put("productType", "PREPAINTED");
        s.put("edition", "LIMITED"); s.put("limited", true); s.put("accessories", List.of("替換手部", " ", "專用底座"));
        s.put("releaseMonth", "2027-03"); s.put("specNote", "規格以實際出貨為準");
        return s;
    }

    @Test void specsRoundTripAndPublicView() throws Exception {
        var a = admin();
        var created = data(ok(a, "POST", "/admin/products", product(fullSpecs()), 201));
        products.add(created.path("id").asText());
        var s = created.path("specs");
        assertEquals("1/7", s.path("scale").asText()); assertEquals(245, s.path("heightMm").asInt());
        assertEquals(List.of("PVC", "ABS"), List.of(s.path("materials").get(0).asText(), s.path("materials").get(1).asText()), "代碼正規化為大寫");
        assertEquals(2, s.path("accessories").size(), "空白配件去除");
        assertTrue(s.path("limited").asBoolean()); assertEquals("REAL", created.path("contentOrigin").asText());
        String id = created.path("id").asText(); var sku = created.path("skus").get(0).path("id").asText();
        ok(a, "POST", "/admin/products/" + id + "/skus/" + sku + "/inventory/adjustments", Map.of("requestId", uuid(), "expectedVersion", 0, "delta", 2, "reason", "RESTOCK", "note", "入庫"), 201);
        ok(a, "PATCH", "/admin/products/" + id + "/publication", Map.of("expectedVersion", created.path("version").asInt(), "published", true), 200);
        var pub = data(ok(guest(), "GET", "/catalog/products/" + id, null, 200));
        assertEquals("IN_STOCK", pub.path("availability").asText()); assertEquals(3200, pub.path("minPriceTwd").asInt());
        assertEquals("月梨", pub.path("specs").path("characterName").asText()); assertEquals("2027-03", pub.path("specs").path("releaseMonth").asText());
        assertTrue(pub.path("specs").path("releaseDate").isNull());
    }

    @Test void oldClientsWithoutSpecsKeepExistingSpecsAndUnknownSpecsStayEmpty() throws Exception {
        var a = admin();
        var created = data(ok(a, "POST", "/admin/products", product(fullSpecs()), 201)); String id = created.path("id").asText(); products.add(id);
        var update = product(null); update.put("expectedVersion", created.path("version").asInt()); update.put("title", "改名但不送規格");
        update.put("skus", List.of(Map.of("id", created.path("skus").get(0).path("id").asText(), "code", created.path("skus").get(0).path("code").asText(), "label", "標準款", "priceTwd", 3300, "enabled", true)));
        var after = data(ok(a, "PUT", "/admin/products/" + id, update, 200));
        assertEquals("1/7", after.path("specs").path("scale").asText(), "未送 specs＝不變更");
        // 沒有規格資料的新商品：全部為未提供，不杜撰
        var bare = data(ok(a, "POST", "/admin/products", product(null), 201)); products.add(bare.path("id").asText());
        var b = bare.path("specs");
        assertTrue(b.path("scale").isNull()); assertTrue(b.path("heightMm").isNull()); assertTrue(b.path("manufacturer").isNull());
        assertEquals(0, b.path("materials").size()); assertFalse(b.path("limited").asBoolean()); assertEquals("REAL", bare.path("contentOrigin").asText());
    }

    @Test void whitelistValidation() throws Exception {
        var a = admin();
        for (var bad : List.<Map<String, Object>>of(Map.of("scale", "1/9"), Map.of("materials", List.of("WOOD")), Map.of("productType", "CAR"), Map.of("edition", "GOLD"),
                Map.of("heightMm", 0), Map.of("releaseMonth", "2027-13"), Map.of("releaseDate", "2027-03-01", "releaseMonth", "2027-03"),
                Map.of("conceptMaterials", List.of("PVC")), Map.of("contentOrigin", "FAKE"), Map.of("price", 1))) {
            assertEquals(400, call(a, "POST", "/admin/products", product(new HashMap<>(bad))).getResponse().getStatus(), bad.toString());
        }
    }

    @Test void aiConceptMaterialsAreNotPublishedAsRealSpecs() throws Exception {
        var a = admin();
        var specs = new HashMap<String, Object>(Map.of("contentOrigin", "AI_CONCEPT", "conceptMaterials", List.of("PVC", "RESIN"), "materials", List.of("PVC")));
        var created = data(ok(a, "POST", "/admin/products", product(specs), 201)); String id = created.path("id").asText(); products.add(id);
        assertEquals("AI_CONCEPT", created.path("contentOrigin").asText());
        ok(a, "PATCH", "/admin/products/" + id + "/publication", Map.of("expectedVersion", created.path("version").asInt(), "published", true), 200);
        var pub = data(ok(guest(), "GET", "/catalog/products/" + id, null, 200));
        assertEquals(0, pub.path("specs").path("materials").size(), "AI 概念商品不公開製造材質");
        assertEquals(2, pub.path("specs").path("conceptMaterials").size());
        assertEquals("SOLD_OUT", pub.path("availability").asText(), "無庫存且無預購");
        // 開放預購批次後為 PREORDER，並帶預購開始／截止
        var b = batch(a, new Fixture(id, created.path("skus").get(0).path("id").asText()), 5, 3200, true);
        var again = data(ok(guest(), "GET", "/catalog/products/" + id, null, 200));
        assertEquals("PREORDER", again.path("availability").asText());
        assertEquals(b.path("id").asText(), again.path("preorder").path("batchId").asText());
        assertFalse(again.path("preorder").path("deadlineAt").isNull()); assertFalse(again.path("preorder").path("openedAt").isNull());
    }
}
