package dev.jackteng.figura.controller;

import dev.jackteng.figura.pojo.Result;
import dev.jackteng.figura.service.GuideCatalog;
import dev.jackteng.figura.service.SeoService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;

/**
 * Commerce V2 Phase 4：公開 SEO 端點（GET、不需登入、不讀 Cookie）。
 * head／body 由 nginx SSI 以子請求呼叫，原始網址放在 X-Seo-Uri；測試或除錯可用 ?path=。
 */
@RestController @RequestMapping("/seo")
public class SeoController {
    private static final MediaType HTML = new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8);
    private final SeoService seo;
    public SeoController(SeoService seo) { this.seo = seo; }
    private SeoService.Page page(String header, String path) { return seo.resolve(header != null && !header.isBlank() ? header : path); }
    private static CacheControl cache() { return CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic(); }

    @GetMapping("/head") public ResponseEntity<String> head(@RequestHeader(value = "X-Seo-Uri", required = false) String uri, @RequestParam(required = false) String path) {
        return ResponseEntity.ok().contentType(HTML).cacheControl(cache()).body(seo.head(page(uri, path)));
    }
    @GetMapping("/body") public ResponseEntity<String> body(@RequestHeader(value = "X-Seo-Uri", required = false) String uri, @RequestParam(required = false) String path) {
        return ResponseEntity.ok().contentType(HTML).cacheControl(cache()).body(seo.body(page(uri, path)));
    }
    /** SPA 用：與 SSI 相同的 title／description／canonical／robots／JSON-LD，讓用戶端導覽時更新 head。 */
    @GetMapping("/meta") public Result<SeoService.Page> meta(@RequestParam String path) { var p = seo.resolve(path); return Result.success(new SeoService.Page(p.title(), p.description(), p.canonical(), p.index(), p.ogType(), p.image(), p.jsonLd(), "", p.found())); }
    @GetMapping(value = "/sitemap.xml") public ResponseEntity<String> sitemap() { return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).cacheControl(cache()).body(seo.sitemap()); }
    @GetMapping(value = "/robots.txt") public ResponseEntity<String> robots() { return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).cacheControl(cache()).body(seo.robots()); }
    @GetMapping("/guides") public Result<List<GuideCatalog.Guide>> guides() { return Result.success(GuideCatalog.ALL); }
    @GetMapping("/guides/{slug}") public Result<GuideCatalog.Guide> guide(@PathVariable String slug) {
        var g = GuideCatalog.BY_SLUG.get(slug); if (g == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到指南"); return Result.success(g);
    }
}
