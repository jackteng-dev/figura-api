package dev.jackteng.figura.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Commerce V2 Phase 7：營運報表（不是 BI），低庫存與營運狀態。
 * 銷售統計一律把「模擬付款」「綠界測試環境」與「真實收款」分開；目前沒有正式金流，真實營收為 0，畫面須清楚標示。
 * 錯誤監控：未處理例外記錄錯誤代碼與類型（不含個資與內容），保留最近 50 筆與累計數，供營運狀態頁與日誌比對。
 */
@Service
public class OpsService {
    private final JdbcTemplate db; private final StringRedisTemplate redis; private final boolean ecpayLive; private final StaffMfaService mfa;
    private static final AtomicLong ERRORS = new AtomicLong();
    private static final ConcurrentLinkedDeque<Map<String, Object>> RECENT = new ConcurrentLinkedDeque<>();
    private dev.jackteng.figura.config.SandboxMode sandbox; private SandboxDemoService demo;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSandbox(dev.jackteng.figura.config.SandboxMode sandbox, SandboxDemoService demo) { this.sandbox = sandbox; this.demo = demo; }
    public OpsService(JdbcTemplate db, StringRedisTemplate redis, @Value("${shop.payments.ecpay.live:false}") boolean ecpayLive, StaffMfaService mfa) { this.db = db; this.redis = redis; this.ecpayLive = ecpayLive; this.mfa = mfa; }

    /** 由 GlobalExceptionHandler 呼叫；只記錄錯誤代碼、類型、路徑與時間。 */
    public static String recordError(Throwable e, String path) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        ERRORS.incrementAndGet();
        var m = new LinkedHashMap<String, Object>(); m.put("errorId", id); m.put("type", e.getClass().getSimpleName()); m.put("path", path == null ? "" : path.replaceAll("[0-9a-f]{8}-[0-9a-f-]{27}", "{id}")); m.put("at", LocalDateTime.now().toString());
        RECENT.addFirst(m); while (RECENT.size() > 50) RECENT.pollLast();
        return id;
    }

    public record Revenue(String source, String label, boolean real, long orders, long amountTwd) {}
    public record Ranked(String productId, String title, long quantity, long amountTwd) {}
    public record SalesReport(LocalDate from, LocalDate to, List<Revenue> revenue, long realRevenueTwd, List<Ranked> topProducts, String note) {}

    @Transactional(readOnly = true)
    public SalesReport sales(LocalDate from, LocalDate to) {
        if (from == null) from = LocalDate.now().minusDays(29);
        if (to == null) to = LocalDate.now();
        if (to.isBefore(from) || from.plusDays(366).isBefore(to)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期範圍不正確（最多一年）");
        var start = from.atStartOfDay(); var end = to.plusDays(1).atStartOfDay();
        var out = new ArrayList<Revenue>();
        // 現貨：依付款 provider 分類
        for (var r : db.queryForList("SELECT p.provider,COUNT(DISTINCT p.order_id) n,COALESCE(SUM(p.amount_twd),0) amt FROM mock_payment p WHERE p.status='SUCCEEDED' AND p.settled_at>=? AND p.settled_at<? GROUP BY p.provider", start, end)) {
            String provider = (String) r.get("provider"); boolean real = "ECPAY".equals(provider) && ecpayLive;
            out.add(new Revenue("STOCK_" + provider, "現貨・" + (provider.equals("MOCK") ? "模擬付款（非真實營收）" : real ? "綠界（正式）" : "綠界測試環境（非真實營收）"), real, n(r.get("n")), n(r.get("amt"))));
        }
        // 預購：目前只有模擬付款
        var pre = db.queryForMap("SELECT COUNT(DISTINCT order_id) n,COALESCE(SUM(amount_twd),0) amt FROM preorder_mock_payment WHERE status='SUCCEEDED' AND settled_at>=? AND settled_at<?", start, end);
        out.add(new Revenue("PREORDER_MOCK", "預購・模擬付款（非真實營收）", false, n(pre.get("n")), n(pre.get("amt"))));
        long real = out.stream().filter(Revenue::real).mapToLong(Revenue::amountTwd).sum();
        // 排行：已付款（含後來退款者不計）現貨明細＋預購件數
        var top = db.query("""
                SELECT product_id,MAX(title) title,SUM(q) qty,SUM(a) amt FROM (
                  SELECT l.product_id,l.title,l.quantity q,l.quantity*l.unit_price_twd a FROM customer_order_line l JOIN customer_order o ON o.id=l.order_id
                   WHERE o.status='PAID' AND o.paid_at>=? AND o.paid_at<?
                  UNION ALL
                  SELECT b.product_id,p.product_title,p.quantity,p.subtotal_twd FROM preorder_order p JOIN preorder_batch b ON b.id=p.batch_id
                   WHERE p.status='PAID' AND p.paid_at>=? AND p.paid_at<?
                ) x GROUP BY product_id ORDER BY qty DESC,amt DESC LIMIT 10""",
                (rs, i) -> new Ranked(rs.getString("product_id"), rs.getString("title"), rs.getLong("qty"), rs.getLong("amt")), start, end, start, end);
        return new SalesReport(from, to, out, real, top, real == 0 ? "目前沒有正式金流收款；以上金額皆為模擬或測試環境，不可作為營收。" : "真實營收只計正式金流。");
    }
    private static long n(Object v) { return v == null ? 0 : ((Number) v).longValue(); }

    public record LowStock(String productId, String title, String status, String skuId, String label, int quantity, int reserved, int available) {}
    @Transactional(readOnly = true)
    public List<LowStock> lowStock(int threshold) {
        if (threshold < 0 || threshold > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "門檻不正確");
        return db.query("""
                SELECT p.id,p.title,p.status,s.id sku,s.label,i.quantity,i.reserved FROM catalog_sku s JOIN catalog_product p ON p.id=s.product_id JOIN sku_inventory i ON i.sku_id=s.id
                 WHERE s.enabled=TRUE AND p.status IN ('PUBLISHED','DRAFT') AND i.quantity-i.reserved<=? ORDER BY p.status='PUBLISHED' DESC,i.quantity-i.reserved,p.title LIMIT 200""",
                (rs, i) -> new LowStock(rs.getString("id"), rs.getString("title"), rs.getString("status"), rs.getString("sku"), rs.getString("label"), rs.getInt("quantity"), rs.getInt("reserved"),
                        rs.getInt("quantity") - rs.getInt("reserved")), threshold);
    }

    public record Status(boolean database, boolean redis, Map<String, Long> notifications, long warehouseOverdue, long adjustmentsPending, long ticketsWaiting,
                         long pagesInReview, long serverErrors, List<Map<String, Object>> recentErrors, LocalDateTime checkedAt,
                         boolean mfaAvailable, long staffActive, long staffMfaEnrolled,
                         boolean sandboxMode, int mfaExemptAccounts, long mfaDemoActive) {}
    public Status status() {
        boolean dbOk; try { dbOk = Objects.equals(db.queryForObject("SELECT 1", Integer.class), 1); } catch (Exception e) { dbOk = false; }
        boolean redisOk; try { redisOk = "PONG".equalsIgnoreCase(Objects.requireNonNull(redis.getConnectionFactory()).getConnection().ping()); } catch (Exception e) { redisOk = false; }
        var queue = new LinkedHashMap<String, Long>();
        if (dbOk) for (var r : db.queryForList("SELECT status,COUNT(*) c FROM notification_outbox GROUP BY status")) queue.put((String) r.get("status"), n(r.get("c")));
        return new Status(dbOk, redisOk, queue,
                dbOk ? n(db.queryForObject("SELECT COUNT(*) FROM shipment WHERE held_at IS NOT NULL AND merged_into IS NULL AND storage_until<CURRENT_TIMESTAMP(6)", Long.class)) : -1,
                dbOk ? n(db.queryForObject("SELECT COUNT(*) FROM shipping_adjustment WHERE status='PENDING_REVIEW'", Long.class)) : -1,
                dbOk ? n(db.queryForObject("SELECT COUNT(*) FROM support_ticket WHERE status='WAITING_STAFF'", Long.class)) : -1,
                dbOk ? n(db.queryForObject("SELECT COUNT(*) FROM site_page WHERE status='IN_REVIEW'", Long.class)) : -1,
                ERRORS.get(), List.copyOf(RECENT), LocalDateTime.now(), mfa.available(),
                dbOk ? n(db.queryForObject("SELECT COUNT(*) FROM user WHERE is_stop='F'", Long.class)) : -1,
                dbOk ? n(db.queryForObject("SELECT COUNT(*) FROM user u JOIN staff_mfa m ON m.user_id=u.id AND m.enabled=TRUE WHERE u.is_stop='F'", Long.class)) : -1,
                sandbox != null && sandbox.enabled(), sandbox == null ? 0 : sandbox.exemptCount(), dbOk && demo != null ? demo.activeCount() : -1);
    }
}
