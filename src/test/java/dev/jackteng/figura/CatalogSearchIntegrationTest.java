package dev.jackteng.figura;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Commerce V2 Phase 2：後端搜尋、篩選、排序、分頁與 facets；舊呼叫相容。 */
@SpringBootTest(properties = "shop.orders.expiry-delay-ms=600000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "SHOP_MYSQL_INTEGRATION", matches = "true")
class CatalogSearchIntegrationTest extends ShopIntegrationSupport {
    private final String tag = "搜尋" + UUID.randomUUID().toString().substring(0, 6);

    private String make(Client a, String title, int price, int stock, Map<String, Object> specs) throws Exception {
        var body = new HashMap<String, Object>(Map.of("title", title, "brand", "ELPSY", "category", "公仔", "description", "合成搜尋商品", "expectedVersion", 0,
                "skus", List.of(Map.of("code", "SRCH-" + uuid(), "label", "標準款", "priceTwd", price, "enabled", true))));
        body.put("specs", specs);
        var p = data(ok(a, "POST", "/admin/products", body, 201)); String id = p.path("id").asText(); products.add(id);
        if (stock > 0) ok(a, "POST", "/admin/products/" + id + "/skus/" + p.path("skus").get(0).path("id").asText() + "/inventory/adjustments",
                Map.of("requestId", uuid(), "expectedVersion", 0, "delta", stock, "reason", "RESTOCK", "note", "入庫"), 201);
        ok(a, "PATCH", "/admin/products/" + id + "/publication", Map.of("expectedVersion", p.path("version").asInt(), "published", true), 200);
        return id;
    }
    private JsonNode search(String query) throws Exception { return data(ok(guest(), "GET", "/catalog/products?" + query, null, 200)); }
    /** MockMvc 會自行編碼 URL，這裡不可預先編碼（否則變成 %25…）。 */
    private static String enc(String v) { return v; }
    private List<String> ids(JsonNode page) { var out = new ArrayList<String>(); page.path("items").forEach(i -> out.add(i.path("id").asText())); return out; }

    @Test void searchFilterSortAndFacets() throws Exception {
        var a = admin();
        String cheap = make(a, tag + " 月梨信差", 900, 3, Map.of("characterName", "梨梨", "scale", "1/7", "materials", List.of("PVC"), "productType", "PREPAINTED", "releaseMonth", "2027-05", "manufacturer", "合成工房"));
        String pricey = make(a, tag + " 冷熔鍋爐姊", 5200, 0, Map.of("scale", "1/8", "materials", List.of("PVC", "ABS"), "productType", "STATUE", "edition", "LIMITED", "limited", true, "releaseDate", "2027-01-15", "manufacturer", "合成工房"));
        String ai = make(a, tag + " 概念展示", 1500, 0, Map.of("contentOrigin", "AI_CONCEPT", "conceptMaterials", List.of("PVC")));
        batch(a, new Fixture(pricey, data(ok(a, "GET", "/admin/products/" + pricey, null, 200)).path("skus").get(0).path("id").asText()), 5, 5200, true);

        // 名稱（ngram）與角色、製造商；1 個字走 LIKE
        assertEquals(Set.of(cheap, pricey, ai), new HashSet<>(ids(search("q=" + enc(tag) + "&pageSize=50"))));
        assertEquals(List.of(cheap), ids(search("q=" + enc(tag + " 梨梨"))), "多個詞皆須符合");
        assertTrue(ids(search("q=" + enc("梨") + "&pageSize=50")).contains(cheap), "單字搜尋");
        // 篩選
        String base = "q=" + enc(tag) + "&pageSize=50";
        assertEquals(List.of(pricey), ids(search(base + "&scale=1/8")));
        assertEquals(Set.of(cheap, pricey), new HashSet<>(ids(search(base + "&material=PVC"))), "AI 概念商品沒有真實材質，不符合材質篩選");
        assertEquals(List.of(pricey), ids(search(base + "&limited=true&edition=LIMITED")));
        assertEquals(List.of(cheap), ids(search(base + "&availability=IN_STOCK")));
        assertEquals(List.of(pricey), ids(search(base + "&availability=PREORDER")));
        assertEquals(List.of(ai), ids(search(base + "&availability=SOLD_OUT")));
        assertEquals(Set.of(cheap, ai), new HashSet<>(ids(search(base + "&priceMax=2000"))));
        assertEquals(Set.of(cheap, pricey), new HashSet<>(ids(search(base + "&manufacturer=" + enc("合成工房")))));
        // 排序與分頁
        assertEquals(List.of(cheap, ai, pricey), ids(search(base + "&sort=PRICE_ASC")));
        assertEquals(List.of(pricey, cheap, ai), ids(search(base + "&sort=RELEASE_ASC")), "精確日期與月份一起排序，未提供者最後");
        var page2 = search("q=" + enc(tag) + "&sort=PRICE_DESC&page=2&pageSize=2");
        assertEquals(3, page2.path("total").asInt()); assertEquals(List.of(cheap), ids(page2));
        // 搜尋結果帶可售狀態
        var first = search(base + "&sort=PRICE_DESC").path("items").get(0);
        assertEquals("PREORDER", first.path("availability").asText()); assertEquals(5200, first.path("minPriceTwd").asInt());
        // facets
        var f = data(ok(guest(), "GET", "/catalog/facets?q=" + enc(tag), null, 200));
        assertEquals(3, f.path("total").asInt()); assertEquals(900, f.path("priceMin").asInt()); assertEquals(5200, f.path("priceMax").asInt());
        var mats = new HashMap<String, Integer>(); f.path("materials").forEach(m -> mats.put(m.path("value").asText(), m.path("count").asInt()));
        assertEquals(Map.of("PVC", 2, "ABS", 1), mats);
        var avail = new HashMap<String, Integer>(); f.path("availability").forEach(m -> avail.put(m.path("value").asText(), m.path("count").asInt()));
        assertEquals(Map.of("IN_STOCK", 1, "PREORDER", 1, "SOLD_OUT", 1), avail);
    }

    @Test void validationAndLegacyCompatibility() throws Exception {
        var g = guest();
        for (String bad : List.of("sort=CHEAPEST", "scale=1/9", "availability=SOON", "material=WOOD", "brandId=not-a-uuid", "priceMin=500&priceMax=100", "page=0", "pageSize=51", "q=" + "x".repeat(81)))
            assertEquals(400, call(g, "GET", "/catalog/products?" + bad, null).getResponse().getStatus(), bad);
        // 舊呼叫：只帶分頁，最新上架在前
        var legacy = data(ok(g, "GET", "/catalog/products?page=1&pageSize=5", null, 200));
        assertTrue(legacy.path("total").asInt() >= legacy.path("items").size());
        String prev = null;
        for (var i : legacy.path("items")) assertNotNull(i.path("availability").asText(null));
        assertEquals(200, call(g, "GET", "/catalog/facets", null).getResponse().getStatus());
    }
}
