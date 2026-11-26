package dev.jackteng.figura.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.figura.dto.CatalogPayloads.PublicView;
import dev.jackteng.figura.dto.CatalogPayloads.Search;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Commerce V2 Phase 4：伺服器端 SEO／GEO。
 * - head：title、description、canonical、robots、Open Graph、JSON-LD，給 nginx SSI 注入 index.html。
 * - body：與頁面相同內容的語意化 HTML（商品名稱、規格、可售狀態、指南全文），放在 #app 內，SPA 掛載時取代；無 JS 的爬蟲也讀得到。
 * - sitemap.xml／robots.txt。
 * 不虛構：沒有評價／評分；AI 概念商品不輸出 Product 結構化資料；offers 預設關閉（目前付款為模擬，不能宣稱可購買的報價）。
 */
@Service
public class SeoService {
    static final String SITE = "Figura", DEFAULT_DESC = "Figura 公仔與收藏品：現貨、預購批次、規格清楚標示。本站目前為展示用沙盒，付款與配送皆為模擬演練。";
    private static final Pattern PRODUCT = Pattern.compile("^/shop/products/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$");
    private static final Pattern POLICY = Pattern.compile("^/shop/pages/([a-z0-9-]{1,40})$");
    private static final Pattern GUIDE = Pattern.compile("^/shop/guides/([a-z0-9-]{1,40})$");
    private static final Set<String> NOINDEX_PREFIX = Set.of("/shop/cart", "/shop/checkout", "/shop/buy-now", "/shop/login", "/shop/register", "/shop/forgot-password",
            "/shop/reset-password", "/shop/verify-email", "/shop/account", "/shop/my-orders", "/shop/orders", "/shop/preorder-orders", "/shop/addresses", "/shop/favorites", "/shop/notifications", "/shop/shipments", "/shop/support");
    private final CatalogService catalog; private final JdbcTemplate db; private final ObjectMapper json;
    private final String base; private final boolean offers;

    public SeoService(CatalogService catalog, JdbcTemplate db, ObjectMapper json,
                      @Value("${shop.seo.base-url:https://figura.jackteng.dev}") String base, @Value("${shop.seo.product-offers:false}") boolean offers) {
        this.catalog = catalog; this.db = db; this.json = json; this.base = base.replaceAll("/+$", ""); this.offers = offers;
    }

    public record Page(String title, String description, String canonical, boolean index, String ogType, String image, List<Object> jsonLd, String body, boolean found) {}

    /** 解析原始請求 URI（含 query）。 */
    public Page resolve(String uri) {
        String path = uri == null || uri.isBlank() ? "/" : uri;
        if (path.length() > 2048) path = path.substring(0, 2048);
        String query = "";
        int q = path.indexOf('?'); if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }
        path = path.replaceAll("/+$", ""); if (path.isEmpty()) path = "/";
        var params = parse(query);
        if (path.equals("/shop")) return home();
        if (path.equals("/shop/products")) return list(params);
        if (path.equals("/shop/preorders")) return simple("預購情報｜" + SITE, "Figura 開放中的預購批次：截單時間、預計到貨區間與配額。", "/shop/preorders", true, "<h1>預購情報</h1><p>查看開放中的預購批次、截單時間與預計到貨區間。</p>" + guideLinks());
        if (path.equals("/shop/guides")) return guides();
        var g = GUIDE.matcher(path); if (g.matches()) return guide(g.group(1));
        if (path.equals("/shop/pages")) return simple("政策與說明｜" + SITE, "Figura 的服務條款、隱私權、配送與退換貨說明（以已審核發布的版本為準）。", "/shop/pages", true, "<h1>政策與說明</h1>");
        var pg = POLICY.matcher(path); if (pg.matches()) return policy(pg.group(1));
        var m = PRODUCT.matcher(path); if (m.matches()) return product(m.group(1));
        for (String p : NOINDEX_PREFIX) if (path.equals(p) || path.startsWith(p + "/")) return simple(SITE, DEFAULT_DESC, path, false, "");
        if (path.equals("/") || path.startsWith("/shop/")) return path.startsWith("/shop/") ? notFound(path) : simple(SITE, DEFAULT_DESC, "/shop", false, "");
        // 員工後台與其他非商店路徑：不索引
        return simple(SITE + " 管理後台", "Figura 員工管理後台。", path, false, "");
    }

    private Page home() {
        var latest = catalog.search(Search.none(), 1, 12).items();
        var b = new StringBuilder("<h1>Figura 公仔與收藏品</h1><p>").append(esc(DEFAULT_DESC)).append("</p><h2>最新商品</h2><ul>");
        latest.forEach(p -> b.append("<li><a href=\"/shop/products/").append(p.id()).append("\">").append(esc(p.title())).append("</a></li>"));
        b.append("</ul><p><a href=\"/shop/products\">所有商品</a>・<a href=\"/shop/preorders\">預購情報</a></p>").append(guideLinks());
        var site = new LinkedHashMap<String, Object>();
        site.put("@context", "https://schema.org"); site.put("@type", "WebSite"); site.put("name", SITE); site.put("url", base + "/shop"); site.put("inLanguage", "zh-Hant-TW");
        return new Page("Figura｜公仔與收藏品", DEFAULT_DESC, "/shop", true, "website", null, List.of(site), b.toString(), true);
    }

    private Page list(Map<String, String> params) {
        int page = 1;
        try { page = Math.max(1, Math.min(1000, Integer.parseInt(params.getOrDefault("page", "1")))); } catch (NumberFormatException ignored) {}
        // 只有「全部商品＋分頁」可索引；搜尋與篩選組合 noindex,follow 並 canonical 到全部商品，避免大量重複頁
        boolean filtered = params.keySet().stream().anyMatch(k -> !k.equals("page"));
        String canonical = "/shop/products" + (page > 1 && !filtered ? "?page=" + page : "");
        var result = catalog.search(Search.none(), page, 24);
        var b = new StringBuilder("<h1>所有商品</h1><ul>");
        result.items().forEach(p -> b.append("<li><a href=\"/shop/products/").append(p.id()).append("\">").append(esc(p.title())).append("</a>")
                .append(p.availability() == null ? "" : "（" + esc(availability(p.availability())) + "）").append("</li>"));
        b.append("</ul><nav aria-label=\"分頁\">");
        long pages = Math.max(1, (result.total() + 23) / 24);
        if (page > 1) b.append("<a rel=\"prev\" href=\"/shop/products").append(page > 2 ? "?page=" + (page - 1) : "").append("\">上一頁</a> ");
        if (page < pages) b.append("<a rel=\"next\" href=\"/shop/products?page=").append(page + 1).append("\">下一頁</a>");
        b.append("</nav>");
        String title = "所有商品" + (page > 1 ? "（第 " + page + " 頁）" : "") + "｜" + SITE;
        return new Page(title, "瀏覽 Figura 全部公仔與收藏品，依品牌、作品、比例、材質與現貨／預購篩選。", canonical, !filtered && page <= pages, "website", null, List.of(), b.toString(), true);
    }

    private Page product(String id) {
        PublicView p;
        try { p = catalog.publicDetail(id); } catch (ResponseStatusException e) { return notFound("/shop/products/" + id); }
        boolean ai = "AI_CONCEPT".equals(p.contentOrigin());
        var rows = specRows(p, ai);
        String desc = (ai ? "AI 生成概念展示商品。" : "") + p.brand() + (p.work() == null ? "" : "・" + p.work()) + "｜" + p.title() + "。" + availability(p.availability())
                + (rows.stream().anyMatch(r -> r[0].equals("比例")) ? "，" + rows.stream().filter(r -> r[0].equals("比例")).findFirst().get()[1] : "") + "。";
        String image = p.image() == null ? null : base + p.image().url() + "?width=1024";
        var b = new StringBuilder("<nav aria-label=\"麵包屑\"><a href=\"/shop\">首頁</a> › <a href=\"/shop/products\">所有商品</a> › ").append(esc(p.title())).append("</nav>");
        b.append("<article><h1>").append(esc(p.title())).append("</h1><p>").append(esc(availability(p.availability())));
        if (p.minPriceTwd() != null) b.append("・NT$ ").append(String.format("%,d", p.minPriceTwd()));
        b.append("</p>");
        if (ai) b.append("<p><strong>AI 虛構展示商品</strong>：名稱、系列、圖片與設定皆為 AI 生成的概念內容，非實物攝影，也不是實際製造規格。</p>");
        b.append("<h2>商品規格</h2><table><tbody>");
        rows.forEach(r -> b.append("<tr><th scope=\"row\">").append(esc(r[0])).append("</th><td>").append(esc(r[1])).append("</td></tr>"));
        b.append("</tbody></table>");
        if (p.specs() != null && !p.specs().accessories().isEmpty()) { b.append("<h2>配件與附屬品</h2><ul>"); p.specs().accessories().forEach(a -> b.append("<li>").append(esc(a)).append("</li>")); b.append("</ul>"); }
        if (p.preorder() != null) b.append("<h2>預購</h2><p>截單：").append(esc(String.valueOf(p.preorder().deadlineAt()).replace('T', ' '))).append("；預計到貨：")
                .append(p.preorder().arrivalFrom()).append(" 至 ").append(p.preorder().arrivalTo()).append("（預定，可能變更）</p>");
        b.append("<h2>商品介紹</h2>");
        for (String para : p.description().split("\\n+")) if (!para.isBlank()) b.append("<p>").append(esc(para.trim())).append("</p>");
        b.append("</article>").append(guideLinks());

        var ld = new ArrayList<Object>();
        var crumbs = new LinkedHashMap<String, Object>();
        crumbs.put("@context", "https://schema.org"); crumbs.put("@type", "BreadcrumbList");
        crumbs.put("itemListElement", List.of(crumb(1, "首頁", base + "/shop"), crumb(2, "所有商品", base + "/shop/products"), crumb(3, p.title(), base + "/shop/products/" + id)));
        ld.add(crumbs);
        if (!ai) ld.add(productLd(p, rows, image));   // AI 概念商品不宣稱為實體商品
        return new Page(p.title() + "｜" + p.brand() + "｜" + SITE, desc, "/shop/products/" + id, true, ai ? "article" : "product", image, ld, b.toString(), true);
    }

    private Map<String, Object> productLd(PublicView p, List<String[]> rows, String image) {
        var m = new LinkedHashMap<String, Object>();
        m.put("@context", "https://schema.org"); m.put("@type", "Product"); m.put("name", p.title()); m.put("url", base + "/shop/products/" + p.id());
        m.put("description", p.description().length() > 500 ? p.description().substring(0, 500) : p.description());
        if (image != null) m.put("image", image);
        m.put("brand", Map.of("@type", "Brand", "name", p.brand())); m.put("category", p.category());
        if (p.specs() != null && p.specs().manufacturer() != null) m.put("manufacturer", Map.of("@type", "Organization", "name", p.specs().manufacturer()));
        if (p.specs() != null && !p.specs().materials().isEmpty()) m.put("material", String.join("、", p.specs().materials().stream().map(SeoService::material).toList()));
        if (p.specs() != null && p.specs().heightMm() != null) m.put("height", Map.of("@type", "QuantitativeValue", "value", p.specs().heightMm(), "unitCode", "MMT"));
        var props = new ArrayList<Object>();
        for (String[] r : rows) if (Set.of("比例", "商品類型", "版本", "角色", "作品／系列", "預計發售").contains(r[0])) props.add(Map.of("@type", "PropertyValue", "name", r[0], "value", r[1]));
        if (!props.isEmpty()) m.put("additionalProperty", props);
        if (offers && p.minPriceTwd() != null) {
            var o = new LinkedHashMap<String, Object>();
            o.put("@type", "Offer"); o.put("priceCurrency", "TWD"); o.put("price", p.minPriceTwd()); o.put("url", base + "/shop/products/" + p.id());
            o.put("availability", "https://schema.org/" + switch (String.valueOf(p.availability())) { case "IN_STOCK" -> "InStock"; case "PREORDER" -> "PreOrder"; default -> "SoldOut"; });
            m.put("offers", o);
        }
        return m;
    }

    private Page guides() {
        var b = new StringBuilder("<h1>購物指南與知識</h1><ul>");
        GuideCatalog.ALL.forEach(g -> b.append("<li><a href=\"/shop/guides/").append(g.slug()).append("\">").append(esc(g.title())).append("</a>：").append(esc(g.description())).append("</li>"));
        b.append("</ul>");
        return new Page("購物指南與知識｜" + SITE, "預購規則、公仔材質、比例尺寸與購物流程說明。", "/shop/guides", true, "website", null, List.of(), b.toString(), true);
    }

    private Page guide(String slug) {
        var g = GuideCatalog.BY_SLUG.get(slug);
        if (g == null) return notFound("/shop/guides/" + slug);
        var b = new StringBuilder("<nav aria-label=\"麵包屑\"><a href=\"/shop\">首頁</a> › <a href=\"/shop/guides\">購物指南</a> › ").append(esc(g.title())).append("</nav><article><h1>")
                .append(esc(g.title())).append("</h1><p>").append(esc(g.description())).append("</p>");
        for (var s : g.sections()) { b.append("<h2>").append(esc(s.heading())).append("</h2>"); s.paragraphs().forEach(p -> b.append("<p>").append(esc(p)).append("</p>")); }
        if (!g.faqs().isEmpty()) { b.append("<h2>常見問題</h2>"); g.faqs().forEach(f -> b.append("<h3>").append(esc(f.question())).append("</h3><p>").append(esc(f.answer())).append("</p>")); }
        b.append("<p>最後更新：").append(g.updated()).append("</p></article>");
        var ld = new ArrayList<Object>();
        var article = new LinkedHashMap<String, Object>();
        article.put("@context", "https://schema.org"); article.put("@type", "Article"); article.put("headline", g.title()); article.put("description", g.description());
        article.put("dateModified", g.updated()); article.put("inLanguage", "zh-Hant-TW"); article.put("mainEntityOfPage", base + "/shop/guides/" + slug);
        article.put("publisher", Map.of("@type", "Organization", "name", SITE));
        ld.add(article);
        if (!g.faqs().isEmpty()) {
            var faq = new LinkedHashMap<String, Object>();
            faq.put("@context", "https://schema.org"); faq.put("@type", "FAQPage");
            faq.put("mainEntity", g.faqs().stream().map(f -> Map.of("@type", "Question", "name", f.question(), "acceptedAnswer", Map.of("@type", "Answer", "text", f.answer()))).toList());
            ld.add(faq);
        }
        return new Page(g.title() + "｜" + SITE, g.description(), "/shop/guides/" + slug, true, "article", null, ld, b.toString(), true);
    }

    /** 只有審核發布的政策頁可索引；未發布的頁面 noindex，不輸出草稿。 */
    private Page policy(String slug) {
        var rows = db.queryForList("SELECT published_title,published_body,published_revision FROM site_page WHERE slug=?", slug);
        if (rows.isEmpty()) return notFound("/shop/pages/" + slug);
        var r = rows.get(0);
        if (((Number) r.get("published_revision")).intValue() == 0) return simple("條款審核中｜" + SITE, "這個頁面的正式內容審核中。", "/shop/pages/" + slug, false, "<h1>條款審核中</h1>");
        String title = (String) r.get("published_title"), body = (String) r.get("published_body");
        var b = new StringBuilder("<article><h1>").append(esc(title)).append("</h1>");
        for (String para : body.split("\\n+")) if (!para.isBlank()) b.append("<p>").append(esc(para.trim())).append("</p>");
        String desc = body.replaceAll("[\\s]+", " ").trim();
        return simple(title + "｜" + SITE, desc.length() > 120 ? desc.substring(0, 120) + "…" : desc, "/shop/pages/" + slug, true, b.append("</article>").toString());
    }

    private Page notFound(String path) {
        return new Page("找不到頁面｜" + SITE, "這個頁面不存在或商品已下架。", path, false, "website", null, List.of(),
                "<h1>找不到頁面</h1><p>這個頁面不存在或商品已下架。</p><p><a href=\"/shop/products\">瀏覽所有商品</a></p>", false);
    }
    private Page simple(String title, String desc, String canonical, boolean index, String body) { return new Page(title, desc, canonical, index, "website", null, List.of(), body, true); }

    // ---- 輸出 ----
    public String head(Page p) {
        var h = new StringBuilder();
        h.append("<title>").append(esc(p.title())).append("</title>\n");
        h.append(meta("name", "description", p.description()));
        h.append(meta("name", "robots", p.index() ? "index,follow,max-image-preview:large" : "noindex,follow"));
        if (p.index()) h.append("<link rel=\"canonical\" href=\"").append(esc(base + p.canonical())).append("\" data-seo>\n");
        h.append(meta("property", "og:site_name", SITE)).append(meta("property", "og:locale", "zh_TW")).append(meta("property", "og:type", p.ogType()))
                .append(meta("property", "og:title", p.title())).append(meta("property", "og:description", p.description())).append(meta("property", "og:url", base + p.canonical()));
        if (p.image() != null) h.append(meta("property", "og:image", p.image())).append(meta("name", "twitter:card", "summary_large_image"));
        else h.append(meta("name", "twitter:card", "summary"));
        for (Object ld : p.jsonLd()) {
            try { h.append("<script type=\"application/ld+json\" data-seo>").append(json.writeValueAsString(ld).replace("</", "<\\/")).append("</script>\n"); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        return h.toString();
    }
    public String body(Page p) { return p.body().isEmpty() ? "" : "<div id=\"seo-prerender\">" + p.body() + "</div>"; }

    public String sitemap() {
        var x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (String p : List.of("/shop", "/shop/products", "/shop/preorders", "/shop/guides")) x.append(url(p, null));
        GuideCatalog.ALL.forEach(g -> x.append(url("/shop/guides/" + g.slug(), g.updated())));
        db.query("SELECT slug,DATE_FORMAT(published_at,'%Y-%m-%d') d FROM site_page WHERE published_revision>0 ORDER BY slug", rs -> { x.append(url("/shop/pages/" + rs.getString("slug"), rs.getString("d"))); });
        // 只列上架且有啟用款式的商品；lastmod 取商品更新時間
        db.query("SELECT p.id,DATE_FORMAT(p.updated_at,'%Y-%m-%d') d FROM catalog_product p WHERE p.status='PUBLISHED' AND EXISTS(SELECT 1 FROM catalog_sku s WHERE s.product_id=p.id AND s.enabled=TRUE) ORDER BY p.created_at DESC,p.id LIMIT 45000",
                rs -> { x.append(url("/shop/products/" + rs.getString("id"), rs.getString("d"))); });
        return x.append("</urlset>\n").toString();
    }
    public String robots() {
        return """
                # Figura：只開放公開商店頁；會員、購物車、結帳與員工後台不索引。
                # 與 API /api/seo/robots.txt 相同；Googlebot、Bingbot、OAI-SearchBot 適用相同規則。
                User-agent: *
                Allow: /shop
                Allow: /api/catalog/products/
                Disallow: /shop/cart
                Disallow: /shop/checkout
                Disallow: /shop/account
                Disallow: /shop/my-orders
                Disallow: /shop/orders
                Disallow: /shop/preorder-orders
                Disallow: /shop/addresses
                Disallow: /shop/favorites
                Disallow: /shop/notifications
                Disallow: /admin
                Disallow: /api/
                Allow: /api/seo/sitemap.xml

                Sitemap: %s/api/seo/sitemap.xml
                """.formatted(base);
    }

    // ---- 工具 ----
    private String url(String path, String lastmod) { return "<url><loc>" + esc(base + path) + "</loc>" + (lastmod == null ? "" : "<lastmod>" + lastmod + "</lastmod>") + "</url>\n"; }
    private static Map<String, Object> crumb(int pos, String name, String item) { return Map.of("@type", "ListItem", "position", pos, "name", name, "item", item); }
    private static String meta(String attr, String key, String content) { return "<meta " + attr + "=\"" + key + "\" content=\"" + esc(content) + "\" data-seo>\n"; }
    private String guideLinks() {
        var b = new StringBuilder("<nav aria-label=\"購物指南\"><h2>購物指南</h2><ul>");
        GuideCatalog.ALL.forEach(g -> b.append("<li><a href=\"/shop/guides/").append(g.slug()).append("\">").append(esc(g.title())).append("</a></li>"));
        return b.append("</ul></nav>").toString();
    }
    static String availability(String a) { return a == null ? "" : switch (a) { case "IN_STOCK" -> "現貨"; case "PREORDER" -> "預購中"; default -> "售完"; }; }
    static String material(String m) { return switch (m) { case "RESIN" -> "樹脂"; case "POLYSTONE" -> "聚石"; case "METAL" -> "金屬"; case "FABRIC" -> "布料"; case "OTHER" -> "其他"; default -> m; }; }
    private static String type(String t) { return switch (t) { case "PREPAINTED" -> "完成品"; case "ACTION_FIGURE" -> "可動模型"; case "MODEL_KIT" -> "組裝模型"; case "GARAGE_KIT" -> "GK 套件"; case "PRIZE" -> "景品"; case "BLIND_BOX" -> "盒玩"; case "STATUE" -> "雕像"; case "PLUSH" -> "布偶"; default -> "其他"; }; }
    private static String edition(String e) { return switch (e) { case "STANDARD" -> "一般版"; case "LIMITED" -> "限定版"; case "BONUS" -> "特典版"; default -> "豪華版"; }; }
    /** 與 Web 商品頁規格表相同的列：只列有資料的欄位。 */
    static List<String[]> specRows(PublicView p, boolean ai) {
        var s = p.specs(); var rows = new ArrayList<String[]>();
        java.util.function.BiConsumer<String, String> add = (k, v) -> { if (v != null && !v.isBlank()) rows.add(new String[]{k, v}); };
        add.accept("角色", s == null ? null : s.characterName()); add.accept("作品／系列", p.work()); add.accept("品牌", p.brand());
        add.accept("製造商", s == null ? null : s.manufacturer()); add.accept("分類", p.category());
        if (s != null) {
            add.accept("商品類型", s.productType() == null ? null : type(s.productType()));
            add.accept("比例", s.scale() == null ? null : s.scale().equals("NON_SCALE") ? "無比例" : s.scale());
            add.accept("全高", s.heightMm() == null ? null : "約 " + s.heightMm() + " mm"); add.accept("尺寸", s.dimensions());
            add.accept("材質", s.materials().isEmpty() ? null : String.join("、", s.materials().stream().map(SeoService::material).toList()));
            if (ai && !s.conceptMaterials().isEmpty()) add.accept("設定材質（概念）", String.join("、", s.conceptMaterials().stream().map(SeoService::material).toList()) + "（AI 概念設定，非實際製造規格）");
            add.accept("版本", s.edition() == null ? null : edition(s.edition())); add.accept("限定商品", s.limited() ? "是" : null);
            add.accept("預計發售", s.releaseDate() != null ? s.releaseDate().toString().replace('-', '/') + "（預定，可能變更）" : s.releaseMonth() != null ? s.releaseMonth().replace("-", " 年 ") + " 月（預定，可能變更）" : null);
        }
        add.accept("款式", String.join("、", p.skus().stream().map(x -> x.label()).toList()));
        return rows;
    }
    private static Map<String, String> parse(String query) {
        var m = new LinkedHashMap<String, String>();
        for (String part : query.split("&")) {
            if (part.isEmpty()) continue;
            int i = part.indexOf('=');
            try { m.putIfAbsent(URLDecoder.decode(i < 0 ? part : part.substring(0, i), StandardCharsets.UTF_8), i < 0 ? "" : URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException ignored) {}
        }
        return m;
    }
    static String esc(String s) {
        if (s == null) return "";
        var b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) switch (c) { case '<' -> b.append("&lt;"); case '>' -> b.append("&gt;"); case '&' -> b.append("&amp;"); case '"' -> b.append("&quot;"); case '\'' -> b.append("&#39;"); default -> b.append(c); }
        return b.toString();
    }
}
