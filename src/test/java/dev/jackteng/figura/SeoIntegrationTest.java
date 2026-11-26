package dev.jackteng.figura;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Commerce V2 Phase 4：SSI head／body、sitemap、robots、指南；不虛構評價與報價，AI 概念商品不輸出 Product。 */
@SpringBootTest(properties = "shop.orders.expiry-delay-ms=600000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE) @ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "SHOP_MYSQL_INTEGRATION", matches = "true")
class SeoIntegrationTest extends ShopIntegrationSupport {
    private String seo(String endpoint, String uri) throws Exception {
        var r = mvc.perform(get("/figura/api/seo/" + endpoint).contextPath("/figura").servletPath("/api/seo/" + endpoint).header("X-Seo-Uri", uri)).andReturn();
        assertEquals(200, r.getResponse().getStatus()); return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
    private String plain(String endpoint) throws Exception {
        var r = mvc.perform(get("/figura/api/seo/" + endpoint).contextPath("/figura").servletPath("/api/seo/" + endpoint)).andReturn();
        assertEquals(200, r.getResponse().getStatus()); return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test void productHeadAndBodyAreTruthful() throws Exception {
        var a = admin();
        var specs = new HashMap<String, Object>(Map.of("scale", "1/7", "heightMm", 240, "materials", List.of("PVC", "ABS"), "manufacturer", "合成製作所"));
        var real = data(ok(a, "POST", "/admin/products", Map.of("title", "SEO <真實> 商品 " + uuid(), "brand", "ELPSY", "category", "公仔", "description", "第一段\n第二段 & 結尾", "expectedVersion", 0,
                "skus", List.of(Map.of("code", "SEO-" + uuid(), "label", "標準款", "priceTwd", 4200, "enabled", true)), "specs", specs), 201));
        String id = real.path("id").asText(); products.add(id);
        ok(a, "PATCH", "/admin/products/" + id + "/publication", Map.of("expectedVersion", real.path("version").asInt(), "published", true), 200);
        String head = seo("head", "/shop/products/" + id + "?utm=x");
        assertTrue(head.contains("<title>SEO &lt;真實&gt; 商品"), head);
        assertTrue(head.contains("rel=\"canonical\" href=\"https://figura.jackteng.dev/shop/products/" + id + "\""));
        assertTrue(head.contains("\"@type\":\"Product\"")); assertTrue(head.contains("\"material\":\"PVC、ABS\"")); assertTrue(head.contains("\"name\":\"比例\",\"value\":\"1/7\"") || head.contains("\"value\":\"1/7\""));
        assertFalse(head.contains("\"offers\""), "付款為模擬時不宣稱報價");
        assertFalse(head.contains("aggregateRating") || head.contains("\"review\""), "不虛構評價");
        assertTrue(head.contains("index,follow"));
        String body = seo("body", "/shop/products/" + id);
        assertTrue(body.startsWith("<div id=\"seo-prerender\">")); assertTrue(body.contains("<h1>SEO &lt;真實&gt; 商品")); assertTrue(body.contains("第二段 &amp; 結尾"));
        assertTrue(body.contains("<th scope=\"row\">比例</th><td>1/7</td>")); assertFalse(body.contains("<script"));
        assertTrue(plain("sitemap.xml").contains("/shop/products/" + id + "</loc>"));

        // AI 概念商品：不輸出 Product、標示為 AI，概念材質不當作材質
        var ai = data(ok(a, "POST", "/admin/products", Map.of("title", "SEO AI " + uuid(), "brand", "ELPSY", "category", "公仔", "description", "AI 生成概念圖｜展示商品\n概念", "expectedVersion", 0,
                "skus", List.of(Map.of("code", "SEOAI-" + uuid(), "label", "標準款", "priceTwd", 900, "enabled", true)), "specs", Map.of("contentOrigin", "AI_CONCEPT", "conceptMaterials", List.of("RESIN"))), 201));
        String aiId = ai.path("id").asText(); products.add(aiId);
        ok(a, "PATCH", "/admin/products/" + aiId + "/publication", Map.of("expectedVersion", ai.path("version").asInt(), "published", true), 200);
        String aiHead = seo("head", "/shop/products/" + aiId), aiBody = seo("body", "/shop/products/" + aiId);
        assertFalse(aiHead.contains("\"@type\":\"Product\"")); assertTrue(aiHead.contains("AI 生成概念展示商品")); assertTrue(aiHead.contains("BreadcrumbList"));
        assertTrue(aiBody.contains("AI 虛構展示商品")); assertTrue(aiBody.contains("設定材質（概念）")); assertFalse(aiBody.contains("<th scope=\"row\">材質</th>"));

        // 下架：noindex、不在 sitemap
        jdbc.update("UPDATE catalog_product SET status='ARCHIVED' WHERE id=?", id);
        String gone = seo("head", "/shop/products/" + id);
        assertTrue(gone.contains("noindex")); assertFalse(gone.contains("canonical")); assertFalse(plain("sitemap.xml").contains(id));
    }

    @Test void listingGuidesAndPrivatePages() throws Exception {
        String list = seo("head", "/shop/products?page=2");
        assertTrue(list.contains("index,follow")); assertTrue(list.contains("/shop/products?page=2\""));
        String filtered = seo("head", "/shop/products?q=%E4%BF%A1%E5%B7%AE&type=stock");
        assertTrue(filtered.contains("noindex,follow"), "搜尋與篩選組合不索引");
        String guide = seo("head", "/shop/guides/preorder");
        assertTrue(guide.contains("FAQPage")); assertTrue(guide.contains("\"@type\":\"Article\""));
        assertTrue(seo("body", "/shop/guides/preorder").contains("<h2>常見問題</h2>"));
        assertTrue(seo("head", "/shop/guides/nope").contains("noindex"));
        for (String p : List.of("/shop/cart", "/shop/account", "/shop/checkout?x=1", "/admin/products", "/home")) assertTrue(seo("head", p).contains("noindex"), p);
        String robots = plain("robots.txt");
        assertTrue(robots.contains("User-agent: *")); assertTrue(robots.contains("OAI-SearchBot")); assertTrue(robots.contains("Disallow: /shop/cart")); assertTrue(robots.contains("Sitemap: https://figura.jackteng.dev/api/seo/sitemap.xml"));
        var guides = json.readTree(plain("guides")).path("data");
        assertTrue(guides.size() >= 5);
        String sitemap = plain("sitemap.xml");
        assertTrue(sitemap.contains("/shop/guides/materials</loc>")); assertFalse(sitemap.contains("/shop/cart"));
        // 不需登入、未知 header 也安全
        assertTrue(seo("head", "/shop/products/<script>").contains("noindex"));
    }
}
