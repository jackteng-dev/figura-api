package dev.jackteng.figura.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.figura.dto.ShippingPayloads.*;
import dev.jackteng.figura.mapper.FulfillmentMapper;
import dev.jackteng.figura.pojo.CustomUserDetails;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 配送方式與運費規則；超商取貨門市為固定的測試資料（test adapter），不是實際超商系統。 */
@Service
public class ShippingService {
    /** 測試門市清單：只用於開發與驗收。正式上線前需改接真實超商物流的門市選擇。 */
    public static final List<PickupStore> TEST_STORES = List.of(
            new PickupStore("SEVEN", "7-ELEVEN（測試）", "TEST-711-001", "測試門市・台北信義", "台北市信義區測試路 1 號"),
            new PickupStore("SEVEN", "7-ELEVEN（測試）", "TEST-711-002", "測試門市・台中逢甲", "台中市西屯區測試路 2 號"),
            new PickupStore("SEVEN", "7-ELEVEN（測試）", "TEST-711-003", "測試門市・高雄巨蛋", "高雄市左營區測試路 3 號"),
            new PickupStore("FAMILY", "全家（測試）", "TEST-FM-001", "測試門市・台北車站", "台北市中正區測試路 4 號"),
            new PickupStore("FAMILY", "全家（測試）", "TEST-FM-002", "測試門市・新竹科園", "新竹市東區測試路 5 號"));
    private final JdbcTemplate db; private final ObjectMapper json; private final FulfillmentMapper staff;
    public ShippingService(JdbcTemplate db, ObjectMapper json, FulfillmentMapper staff) { this.db = db; this.json = json; this.staff = staff; }
    private final RowMapper<Method> methods = (r, n) -> new Method(r.getString("code"), r.getString("name"), r.getInt("fee_twd"),
            (Integer) r.getObject("free_threshold_twd"), r.getBoolean("enabled"), r.getInt("version"));
    private static ResponseStatusException fail(int code, String message) { return new ResponseStatusException(HttpStatus.valueOf(code), message); }

    public List<Method> methods() { return db.query("SELECT * FROM shipping_method ORDER BY FIELD(code,'HOME','CVS')", methods); }
    public Method method(String code) {
        var found = db.query("SELECT * FROM shipping_method WHERE code=?", methods, code);
        if (found.isEmpty()) throw fail(400, "配送方式不正確");
        return found.get(0);
    }
    /** 一張出貨單的運費：未達免運門檻收基本運費，達到門檻免運。 */
    public static int fee(Method m, long goodsTwd) { return m.freeThresholdTwd() != null && goodsTwd >= m.freeThresholdTwd() ? 0 : m.feeTwd(); }
    public static Optional<PickupStore> store(String id) { return TEST_STORES.stream().filter(s -> s.storeId().equals(id)).findFirst(); }

    @Transactional(readOnly = true) public List<Method> list() { return methods(); }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MethodApplied update(String code, MethodWrite in, CustomUserDetails actor) {
        String username = actor == null ? null : staff.lockActor(actor.getId());
        if (username == null || staff.lockPermission(actor.getId(), "shipping.methods.update").isEmpty()) throw fail(403, "員工或運費設定授權已失效");
        String note = in.note().trim(); if (note.isBlank()) throw fail(400, "異動原因不可空白");
        String hash = hash(Arrays.asList(code, in.expectedVersion(), in.feeTwd(), in.freeThresholdTwd(), in.enabled(), note));
        var old = db.queryForList("SELECT code,actor_id,fingerprint FROM shipping_method_event WHERE request_id=?", in.requestId());
        if (!old.isEmpty()) {
            var e = old.get(0);
            if (!code.equals(e.get("code")) || ((Number) e.get("actor_id")).intValue() != actor.getId() || !hash.equals(e.get("fingerprint"))) throw fail(409, "requestId 已用於不同運費異動");
            return new MethodApplied(method(code), true);
        }
        var found = db.query("SELECT * FROM shipping_method WHERE code=? FOR UPDATE", methods, code);
        if (found.isEmpty()) throw fail(404, "找不到配送方式");
        var before = found.get(0);
        if (before.version() != in.expectedVersion()) throw fail(409, "運費設定已變更，請重讀");
        db.update("UPDATE shipping_method SET fee_twd=?,free_threshold_twd=?,enabled=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE code=?", in.feeTwd(), in.freeThresholdTwd(), in.enabled(), code);
        var after = method(code);
        try {
            db.update("INSERT INTO shipping_method_event(request_id,code,actor_id,actor_username,fingerprint,before_state,after_state,note) VALUES(?,?,?,?,?,?,?,?)",
                    in.requestId(), code, actor.getId(), username, hash, encode(before), encode(after), note);
        } catch (DuplicateKeyException e) { throw fail(409, "requestId 已用於其他運費異動"); }
        return new MethodApplied(after, false);
    }
    private String encode(Object v) { try { return json.writeValueAsString(v); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String hash(Object v) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(v).getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
}
