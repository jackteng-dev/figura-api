package dev.jackteng.figura.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.figura.mapper.FulfillmentMapper;
import dev.jackteng.figura.pojo.CustomUserDetails;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Commerce V2 Phase 6：政策／說明頁。草稿 → 送審 → 發布；發布者不可是最後編輯草稿的人（四眼原則）。
 * 公開端點只回已發布的版本；從未發布的頁面回 published=false，前台顯示「條款審核中」，不顯示草稿。
 * 系統只建立空白草稿，不寫入任何營運承諾。
 */
@Service
public class SitePageService {
    private static final ZoneOffset TAIPEI = ZoneOffset.ofHours(8);
    private final JdbcTemplate db; private final FulfillmentMapper staff; private final ObjectMapper json;
    public SitePageService(JdbcTemplate db, FulfillmentMapper staff, ObjectMapper json) { this.db = db; this.staff = staff; this.json = json; }

    public record PublicPage(String slug, String kind, boolean published, String title, String body, OffsetDateTime publishedAt, int revision) {}
    public record AdminPage(String slug, String kind, String status, String draftTitle, String draftBody, Integer draftEditorId, OffsetDateTime draftUpdatedAt,
                            String publishedTitle, String publishedBody, OffsetDateTime publishedAt, Integer publishedBy, int publishedRevision, int version) {}
    public record Event(String requestId, String slug, int actorId, String actorUsername, String action, String note, int beforeVersion, int afterVersion, OffsetDateTime createdAt) {}
    public record Detail(AdminPage page, List<Event> events) {}
    public record Applied(Detail current, boolean replayed) {}

    private static ResponseStatusException fail(int s, String m) { return new ResponseStatusException(HttpStatus.valueOf(s), m); }
    private static OffsetDateTime time(Object v) { return v == null ? null : (v instanceof java.sql.Timestamp t ? t.toLocalDateTime() : (LocalDateTime) v).atOffset(TAIPEI); }
    private static Integer integer(Object v) { return v == null ? null : ((Number) v).intValue(); }
    private Map<String, Object> row(String slug, boolean lock) {
        if (slug == null || !slug.matches("[a-z0-9-]{1,40}")) throw fail(404, "找不到頁面");
        var rows = db.queryForList("SELECT * FROM site_page WHERE slug=?" + (lock ? " FOR UPDATE" : ""), slug);
        if (rows.isEmpty()) throw fail(404, "找不到頁面"); return rows.get(0);
    }
    private static AdminPage admin(Map<String, Object> r) {
        return new AdminPage((String) r.get("slug"), (String) r.get("kind"), (String) r.get("status"), (String) r.get("draft_title"), (String) r.get("draft_body"),
                integer(r.get("draft_editor_id")), time(r.get("draft_updated_at")), (String) r.get("published_title"), (String) r.get("published_body"),
                time(r.get("published_at")), integer(r.get("published_by")), ((Number) r.get("published_revision")).intValue(), ((Number) r.get("version")).intValue());
    }

    // ---- 公開 ----
    @Transactional(readOnly = true)
    public List<PublicPage> publicList() { return db.queryForList("SELECT * FROM site_page ORDER BY kind DESC, slug").stream().map(SitePageService::pub).toList(); }
    @Transactional(readOnly = true)
    public PublicPage publicPage(String slug) { return pub(row(slug, false)); }
    private static PublicPage pub(Map<String, Object> r) {
        boolean published = ((Number) r.get("published_revision")).intValue() > 0;
        return new PublicPage((String) r.get("slug"), (String) r.get("kind"), published, published ? (String) r.get("published_title") : (String) r.get("draft_title"),
                published ? (String) r.get("published_body") : null, time(r.get("published_at")), ((Number) r.get("published_revision")).intValue());
    }

    // ---- 員工 ----
    @Transactional(readOnly = true) public List<AdminPage> list() { return db.queryForList("SELECT * FROM site_page ORDER BY kind DESC, slug").stream().map(SitePageService::admin).toList(); }
    @Transactional(readOnly = true) public Detail detail(String slug) { return detail(row(slug, false)); }
    private Detail detail(Map<String, Object> r) {
        return new Detail(admin(r), db.query("SELECT * FROM site_page_event WHERE slug=? ORDER BY created_at,request_id", (x, n) -> new Event(x.getString("request_id"), x.getString("slug"),
                x.getInt("actor_id"), x.getString("actor_username"), x.getString("action"), x.getString("note"), x.getInt("before_version"), x.getInt("after_version"), time(x.getObject("created_at", LocalDateTime.class))), r.get("slug")));
    }
    private String authorize(CustomUserDetails actor, String code) {
        String name = actor == null ? null : staff.lockActor(actor.getId());
        if (name == null || staff.lockPermission(actor.getId(), code).isEmpty()) throw fail(403, "員工或頁面授權已失效");
        return name;
    }
    private String hash(Object v) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(v).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private Optional<Applied> prior(String request, CustomUserDetails actor, String slug, String action, String fingerprint) {
        var rows = db.queryForList("SELECT actor_id,slug,action,fingerprint FROM site_page_event WHERE request_id=?", request);
        if (rows.isEmpty()) return Optional.empty();
        var e = rows.get(0);
        if (((Number) e.get("actor_id")).intValue() != actor.getId() || !slug.equals(e.get("slug")) || !action.equals(e.get("action")) || !fingerprint.equals(e.get("fingerprint")))
            throw fail(409, "操作編號已用於不同內容，請重新核對");
        return Optional.of(new Applied(detail(row(slug, false)), true));
    }
    private void record(String request, String slug, CustomUserDetails actor, String username, String action, String fp, String note, int before) {
        try {
            db.update("INSERT INTO site_page_event(request_id,slug,actor_id,actor_username,action,fingerprint,note,before_version,after_version) VALUES(?,?,?,?,?,?,?,?,?)",
                    request, slug, actor.getId(), username, action, fp, note, before, before + 1);
        } catch (DuplicateKeyException e) { throw fail(409, "操作編號已被使用"); }
    }

    /** 編輯草稿：已送審的草稿再編輯會退回 DRAFT（需重新送審）；已發布的內容不受影響直到下一次發布。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Applied edit(String slug, String request, int expectedVersion, String title, String body, String note, CustomUserDetails actor) {
        String username = authorize(actor, "pages.edit");
        String t = title.trim(), b = body.replace("\r\n", "\n").strip(), n = note.trim();
        if (t.isEmpty() || n.isEmpty()) throw fail(400, "標題與修改原因不可空白");
        String fp = hash(List.of("EDIT", slug, expectedVersion, t, b, n));
        var old = prior(request, actor, slug, "EDIT", fp); if (old.isPresent()) return old.get();
        var r = row(slug, true); int v = ((Number) r.get("version")).intValue();
        if (v != expectedVersion) throw fail(409, "頁面已被修改，請重新載入");
        db.update("UPDATE site_page SET draft_title=?,draft_body=?,draft_editor_id=?,draft_updated_at=CURRENT_TIMESTAMP(6),status=IF(status='IN_REVIEW','DRAFT',status),version=version+1 WHERE slug=?",
                t, b, actor.getId(), slug);
        // 已發布頁面編輯後狀態仍是 PUBLISHED（顯示舊版），但草稿與發布版不同；改為 DRAFT 表示有待審變更
        db.update("UPDATE site_page SET status='DRAFT' WHERE slug=? AND status='PUBLISHED' AND (draft_title<>published_title OR draft_body<>published_body)", slug);
        record(request, slug, actor, username, "EDIT", fp, n, v);
        return new Applied(detail(row(slug, false)), false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Applied transition(String slug, String action, String request, int expectedVersion, String note, CustomUserDetails actor) {
        String code = switch (action) { case "SUBMIT" -> "pages.submit"; case "PUBLISH" -> "pages.publish"; case "REJECT" -> "pages.reject"; default -> throw fail(400, "不支援的操作"); };
        String username = authorize(actor, code), n = note.trim();
        if (n.isEmpty()) throw fail(400, "原因不可空白");
        String fp = hash(List.of(action, slug, expectedVersion, n));
        var old = prior(request, actor, slug, action, fp); if (old.isPresent()) return old.get();
        var r = row(slug, true); int v = ((Number) r.get("version")).intValue();
        if (v != expectedVersion) throw fail(409, "頁面已被修改，請重新載入");
        String status = (String) r.get("status");
        switch (action) {
            case "SUBMIT" -> {
                if (!"DRAFT".equals(status)) throw fail(409, "只有草稿可以送審");
                if (((String) r.get("draft_body")).isBlank()) throw fail(409, "內容空白，不能送審");
                db.update("UPDATE site_page SET status='IN_REVIEW',version=version+1 WHERE slug=?", slug);
            }
            case "REJECT" -> {
                if (!"IN_REVIEW".equals(status)) throw fail(409, "只有送審中的頁面可以退回");
                db.update("UPDATE site_page SET status='DRAFT',version=version+1 WHERE slug=?", slug);
            }
            default -> {
                if (!"IN_REVIEW".equals(status)) throw fail(409, "只有送審中的頁面可以發布");
                if (Objects.equals(integer(r.get("draft_editor_id")), actor.getId())) throw fail(409, "發布需由最後編輯者以外的員工審核");
                db.update("UPDATE site_page SET status='PUBLISHED',published_title=draft_title,published_body=draft_body,published_by=?,published_at=CURRENT_TIMESTAMP(6),published_revision=published_revision+1,version=version+1 WHERE slug=?",
                        actor.getId(), slug);
            }
        }
        record(request, slug, actor, username, action, fp, n, v);
        return new Applied(detail(row(slug, false)), false);
    }
}
