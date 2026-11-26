package dev.jackteng.figura.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.figura.dto.EngagementPayloads.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

import static dev.jackteng.figura.dto.EngagementPayloads.*;

/**
 * Commerce V2 Phase 3：收藏、商品提醒訂閱、通知偏好、站內通知匣。
 * owner 一律取驗證過的 CustomerPrincipal；寫入先鎖 customer 列（與既有「customer 先鎖」順序一致），再碰商品。
 * 收藏與提醒只接受「目前上架」的商品；取消永遠可以。站內通知只投影安全欄位。
 */
@Service
public class EngagementService {
    private static final Pattern UUID_RE = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private final JdbcTemplate db;
    private final CatalogService catalog;
    private final ObjectMapper json;
    private final EmailSender email;

    public EngagementService(JdbcTemplate db, CatalogService catalog, ObjectMapper json, EmailSender email) {
        this.db = db; this.catalog = catalog; this.json = json; this.email = email;
    }

    private static ResponseStatusException fail(HttpStatus s, String m) { return new ResponseStatusException(s, m); }
    private static void uuid(String id, String label) { if (id == null || !UUID_RE.matcher(id).matches()) throw fail(HttpStatus.BAD_REQUEST, label + "不正確"); }
    private static void page(int page, int size) { if (page < 1 || page > 10000 || size < 1 || size > 50) throw fail(HttpStatus.BAD_REQUEST, "分頁範圍不正確"); }
    private void lockCustomer(String customer) {
        var rows = db.queryForList("SELECT id FROM customer WHERE id=? AND enabled=TRUE FOR UPDATE", String.class, customer);
        if (rows.isEmpty()) throw fail(HttpStatus.UNAUTHORIZED, "顧客登入狀態無效");
    }
    private boolean published(String product) {
        return !db.queryForList("SELECT id FROM catalog_product WHERE id=? AND status='PUBLISHED'", String.class, product).isEmpty();
    }

    // ---- 收藏 ----
    @Transactional(readOnly = true)
    public dev.jackteng.figura.dto.CatalogPayloads.Page<Favorite> favorites(String customer, int page, int size) {
        page(page, size);
        long total = Objects.requireNonNullElse(db.queryForObject("SELECT COUNT(*) FROM customer_favorite f JOIN catalog_product p ON p.id=f.product_id WHERE f.customer_id=? AND p.status='PUBLISHED'", Long.class, customer), 0L);
        var rows = db.queryForList("SELECT f.product_id, f.created_at FROM customer_favorite f JOIN catalog_product p ON p.id=f.product_id WHERE f.customer_id=? AND p.status='PUBLISHED' ORDER BY f.created_at DESC, f.product_id LIMIT ? OFFSET ?",
                customer, size, (page - 1) * size);
        var ids = rows.stream().map(r -> (String) r.get("product_id")).toList();
        var views = catalog.publicViews(ids);
        var alerts = alertKinds(customer, ids);
        var items = new ArrayList<Favorite>();
        for (var r : rows) {
            var v = views.get((String) r.get("product_id"));
            if (v != null) items.add(new Favorite(v, toTime(r.get("created_at")), alerts.getOrDefault(v.id(), List.of())));
        }
        return new dev.jackteng.figura.dto.CatalogPayloads.Page<>(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public FavoriteIds favoriteIds(String customer) {
        return new FavoriteIds(db.queryForList("SELECT product_id FROM customer_favorite WHERE customer_id=? ORDER BY created_at DESC, product_id LIMIT " + MAX_FAVORITES, String.class, customer));
    }

    @Transactional
    public FavoriteIds favorite(String customer, String product) {
        uuid(product, "商品ID"); lockCustomer(customer);
        boolean exists = !db.queryForList("SELECT product_id FROM customer_favorite WHERE customer_id=? AND product_id=?", String.class, customer, product).isEmpty();
        if (!exists) {
            if (!published(product)) throw fail(HttpStatus.NOT_FOUND, "商品不存在或已下架");
            if (count(customer) >= MAX_FAVORITES) throw fail(HttpStatus.CONFLICT, "收藏已達上限 " + MAX_FAVORITES + " 件，請先整理收藏");
            db.update("INSERT INTO customer_favorite(customer_id,product_id) VALUES(?,?)", customer, product);
        }
        return favoriteIds(customer);
    }

    @Transactional
    public FavoriteIds unfavorite(String customer, String product) {
        uuid(product, "商品ID"); lockCustomer(customer);
        db.update("DELETE FROM customer_favorite WHERE customer_id=? AND product_id=?", customer, product);
        return favoriteIds(customer);
    }

    /** 訪客暫存的收藏在登入後合併：冪等、只收目前上架商品、不超過上限；已存在的視為成功。 */
    @Transactional
    public MergeResult merge(String customer, Merge in) {
        lockCustomer(customer);
        var merged = new ArrayList<String>(); var skipped = new ArrayList<String>();
        long n = count(customer);
        for (String id : new LinkedHashSet<>(in.productIds())) {
            boolean exists = !db.queryForList("SELECT product_id FROM customer_favorite WHERE customer_id=? AND product_id=?", String.class, customer, id).isEmpty();
            if (exists) { merged.add(id); continue; }
            if (n >= MAX_FAVORITES || !published(id)) { skipped.add(id); continue; }
            db.update("INSERT INTO customer_favorite(customer_id,product_id) VALUES(?,?)", customer, id); merged.add(id); n++;
        }
        return new MergeResult(merged, skipped, (int) n);
    }
    private long count(String customer) { return Objects.requireNonNullElse(db.queryForObject("SELECT COUNT(*) FROM customer_favorite WHERE customer_id=?", Long.class, customer), 0L); }

    // ---- 商品提醒 ----
    private Map<String, List<String>> alertKinds(String customer, List<String> products) {
        var out = new HashMap<String, List<String>>();
        if (products.isEmpty()) return out;
        String in = String.join(",", Collections.nCopies(products.size(), "?"));
        var args = new ArrayList<Object>(); args.add(customer); args.addAll(products);
        for (var r : db.queryForList("SELECT product_id,kind FROM customer_product_alert WHERE customer_id=? AND product_id IN (" + in + ") ORDER BY kind", args.toArray()))
            out.computeIfAbsent((String) r.get("product_id"), k -> new ArrayList<>()).add((String) r.get("kind"));
        return out;
    }

    @Transactional(readOnly = true)
    public List<ProductAlert> alerts(String customer) {
        var rows = db.queryForList("SELECT a.product_id,p.title,GROUP_CONCAT(a.kind ORDER BY a.kind) kinds FROM customer_product_alert a JOIN catalog_product p ON p.id=a.product_id WHERE a.customer_id=? GROUP BY a.product_id,p.title ORDER BY MAX(a.created_at) DESC LIMIT " + MAX_ALERT_PRODUCTS, customer);
        var views = catalog.publicViews(rows.stream().map(r -> (String) r.get("product_id")).toList());
        return rows.stream().map(r -> {
            String id = (String) r.get("product_id"); var v = views.get(id);
            return new ProductAlert(id, (String) r.get("title"), List.of(((String) r.get("kinds")).split(",")), v == null ? null : v.availability());
        }).toList();
    }

    /** 以整組取代某商品的提醒種類；空陣列＝全部取消。新增提醒只限目前上架商品。 */
    @Transactional
    public List<String> setAlerts(String customer, String product, AlertSet in) {
        uuid(product, "商品ID");
        var kinds = new TreeSet<String>();
        for (String k : in.kinds()) { String v = k.trim().toUpperCase(Locale.ROOT); if (!ALERT_KINDS.contains(v)) throw fail(HttpStatus.BAD_REQUEST, "提醒種類不正確：" + k); kinds.add(v); }
        lockCustomer(customer);
        var current = new TreeSet<>(db.queryForList("SELECT kind FROM customer_product_alert WHERE customer_id=? AND product_id=?", String.class, customer, product));
        var adding = new TreeSet<>(kinds); adding.removeAll(current);
        if (!adding.isEmpty()) {
            if (!published(product)) throw fail(HttpStatus.NOT_FOUND, "商品不存在或已下架");
            if (current.isEmpty()) {
                long products = Objects.requireNonNullElse(db.queryForObject("SELECT COUNT(DISTINCT product_id) FROM customer_product_alert WHERE customer_id=?", Long.class, customer), 0L);
                if (products >= MAX_ALERT_PRODUCTS) throw fail(HttpStatus.CONFLICT, "商品提醒已達上限 " + MAX_ALERT_PRODUCTS + " 件");
            }
        }
        for (String k : current) if (!kinds.contains(k)) db.update("DELETE FROM customer_product_alert WHERE customer_id=? AND product_id=? AND kind=?", customer, product, k);
        for (String k : adding) db.update("INSERT INTO customer_product_alert(customer_id,product_id,kind) VALUES(?,?,?)", customer, product, k);
        return List.copyOf(kinds);
    }

    // ---- 通知偏好 ----
    @Transactional(readOnly = true)
    public Preferences preferences(String customer) {
        var rows = db.queryForList("SELECT email_enabled,order_updates,product_alerts,version FROM customer_notification_preference WHERE customer_id=?", customer);
        boolean verified = Boolean.TRUE.equals(db.queryForObject("SELECT email IS NOT NULL AND email_verified_at IS NOT NULL FROM customer WHERE id=?", Boolean.class, customer));
        if (rows.isEmpty()) return new Preferences(false, true, true, verified, email.active(), 0);
        var r = rows.get(0);
        return new Preferences(bool(r.get("email_enabled")), bool(r.get("order_updates")), bool(r.get("product_alerts")), verified, email.active(), ((Number) r.get("version")).intValue());
    }

    @Transactional
    public Preferences savePreferences(String customer, PreferencesWrite in) {
        lockCustomer(customer);
        var rows = db.queryForList("SELECT version FROM customer_notification_preference WHERE customer_id=? FOR UPDATE", Integer.class, customer);
        int version = rows.isEmpty() ? 0 : rows.get(0);
        if (version != in.expectedVersion()) throw fail(HttpStatus.CONFLICT, "通知設定已在其他裝置變更，請重新載入");
        if (rows.isEmpty()) db.update("INSERT INTO customer_notification_preference(customer_id,email_enabled,order_updates,product_alerts,version) VALUES(?,?,?,?,1)", customer, in.emailEnabled(), in.orderUpdates(), in.productAlerts());
        else db.update("UPDATE customer_notification_preference SET email_enabled=?,order_updates=?,product_alerts=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE customer_id=?", in.emailEnabled(), in.orderUpdates(), in.productAlerts(), customer);
        return preferences(customer);
    }

    // ---- 站內通知匣 ----
    @Transactional(readOnly = true)
    public Inbox inbox(String customer, int page, int size, boolean unreadOnly) {
        page(page, size);
        String where = " FROM notification_outbox WHERE recipient_ref=?" + (unreadOnly ? " AND read_at IS NULL" : "");
        long total = Objects.requireNonNullElse(db.queryForObject("SELECT COUNT(*)" + where, Long.class, customer), 0L);
        var items = db.query("SELECT id,event_kind,order_id,preorder_order_id,product_id,payload,created_at,read_at" + where + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                (r, n) -> notice(r.getString("id"), r.getString("event_kind"), r.getString("order_id"), r.getString("preorder_order_id"), r.getString("product_id"), r.getString("payload"),
                        r.getObject("created_at", LocalDateTime.class), r.getObject("read_at", LocalDateTime.class)), customer, size, (page - 1) * size);
        return new Inbox(items, page, size, total, unread(customer));
    }
    private long unread(String customer) { return Objects.requireNonNullElse(db.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE recipient_ref=? AND read_at IS NULL", Long.class, customer), 0L); }

    @Transactional
    public ReadResult markRead(String customer, MarkRead in) {
        int updated;
        if (Boolean.TRUE.equals(in.all())) updated = db.update("UPDATE notification_outbox SET read_at=CURRENT_TIMESTAMP(6) WHERE recipient_ref=? AND read_at IS NULL", customer);
        else {
            var ids = in.ids() == null ? List.<String>of() : in.ids().stream().distinct().toList();
            if (ids.isEmpty()) throw fail(HttpStatus.BAD_REQUEST, "請指定通知");
            ids.forEach(id -> uuid(id, "通知ID"));
            var args = new ArrayList<Object>(); args.add(customer); args.addAll(ids);
            updated = db.update("UPDATE notification_outbox SET read_at=CURRENT_TIMESTAMP(6) WHERE recipient_ref=? AND read_at IS NULL AND id IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")", args.toArray());
        }
        return new ReadResult(updated, unread(customer));
    }

    private Notice notice(String id, String kind, String order, String preorder, String product, String payload, LocalDateTime created, LocalDateTime read) {
        JsonNode p; try { p = json.readTree(payload); } catch (Exception e) { p = json.createObjectNode(); }
        var text = NoticeText.of(kind, p);
        return new Notice(id, kind, product != null ? "PRODUCT" : "ORDER", text[0], text[1], order, preorder, product, created, read);
    }
    private static boolean bool(Object v) { return v instanceof Boolean b ? b : v instanceof Number n && n.intValue() != 0; }
    private static LocalDateTime toTime(Object v) { return v instanceof LocalDateTime t ? t : v instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : null; }
}
