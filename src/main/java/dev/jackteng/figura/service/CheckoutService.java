package dev.jackteng.figura.service;

import dev.jackteng.figura.dto.CheckoutPayloads.*;
import dev.jackteng.figura.dto.OrderPayloads;
import dev.jackteng.figura.dto.PreorderOrderPayloads;
import dev.jackteng.figura.dto.ShoppingPayloads;
import dev.jackteng.figura.mapper.CatalogMapper;
import dev.jackteng.figura.mapper.ShoppingMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 統一結帳：購物車裡的現貨合併成一張現貨子單，每個預購批次各一張預購子單（第一版維持一張預購單一個批次）。
 * <p>
 * 整組在<b>同一個資料庫交易</b>建立：任何一張子單失敗（庫存、配額、價格、地址、版本）全部回滾，不會只占到一部分庫存或配額。
 * 重用既有的 {@link OrderService} 與 {@link PreorderOrderService} 建單邏輯（保留庫存／配額、30 分鐘保留、事件與通知都不變），
 * 但先依固定順序上鎖：顧客 → 主檔 guard → 所有相關商品（ID 排序）→ 預購批次（ID 排序），與既有寫入者順序一致，避免死結。
 * <p>
 * 冪等：同一顧客的同一 requestId 只建立一次；內容相同重送回傳目前狀態，內容不同 409。
 */
@Service
public class CheckoutService {
    private static final ZoneOffset TAIPEI = ZoneOffset.ofHours(8);
    private final ShoppingService shopping;
    private final ShoppingMapper shoppingMapper;
    private final CatalogMapper catalog;
    private final CatalogMasterService masters;
    private final OrderService orders;
    private final PreorderOrderService preorders;
    private final JdbcTemplate db;

    public CheckoutService(ShoppingService shopping, ShoppingMapper shoppingMapper, CatalogMapper catalog, CatalogMasterService masters,
                           OrderService orders, PreorderOrderService preorders, JdbcTemplate db) {
        this.shopping = shopping; this.shoppingMapper = shoppingMapper; this.catalog = catalog; this.masters = masters;
        this.orders = orders; this.preorders = preorders; this.db = db;
    }
    private static ResponseStatusException fail(HttpStatus status, String message) { return new ResponseStatusException(status, message); }

    /** 結帳預覽：依購物車即時價格分組，不保留任何庫存或配額。 */
    @Transactional(readOnly = true)
    public Quote quote(String customer) { return quoteOf(shopping.cart(customer)); }

    static Quote quoteOf(ShoppingPayloads.CartView cart) {
        var groups = new ArrayList<QuoteGroup>();
        if (!cart.items().isEmpty()) {
            var lines = cart.items().stream().map(i -> new QuoteLine(i.productId(), i.skuId(), null, i.title(), i.label(), i.quantity(),
                    i.unitPriceTwd() == null ? 0 : i.unitPriceTwd(), i.lineTotalTwd() == null ? 0 : i.lineTotalTwd(), i.issue())).toList();
            groups.add(new QuoteGroup("STOCK", "STOCK", "現貨", null, null, null, null, null, lines, cart.subtotalTwd(), 0, cart.subtotalTwd(),
                    cart.subtotalTwd(), 0, cart.ready(), "FULL"));
        }
        for (var p : cart.preorders()) {
            var line = new QuoteLine(p.productId(), p.skuId(), p.batchId(), p.title(), p.label(), p.quantity(), p.unitPriceTwd(), p.lineTotalTwd(), p.issue());
            String month = p.arrivalFrom() == null ? null : p.arrivalFrom().toString().substring(0, 7);
            long total = p.issue() == null ? p.lineTotalTwd() : 0;
            // 付款條件依批次：訂金批次現在只付訂金，尾款到貨通知後再付
            long now = "DEPOSIT".equals(p.paymentMode()) && p.depositTwd() != null ? Math.min(total, (long) p.depositTwd() * p.quantity()) : total;
            groups.add(new QuoteGroup("PREORDER:" + p.batchId(), "PREORDER", p.batchName(), p.batchId(), p.arrivalFrom(), p.arrivalTo(), month, p.deadlineAt(),
                    List.of(line), total, 0, total, now, total - now, p.issue() == null, p.paymentMode()));
        }
        long subtotal = groups.stream().mapToLong(QuoteGroup::subtotalTwd).sum();
        long payNow = groups.stream().mapToLong(QuoteGroup::payNowTwd).sum(), payLater = groups.stream().mapToLong(QuoteGroup::payLaterTwd).sum();
        return new Quote(cart.version(), groups, subtotal, 0, subtotal, payNow, payLater, cart.checkoutReady());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Placed place(String customer, Place in) {
        if (shoppingMapper.lockCustomer(customer) == null) throw fail(HttpStatus.UNAUTHORIZED, "顧客登入狀態無效");
        String fingerprint = sha256(String.join("|", "CHECKOUT", in.cartVersion().toString(), in.addressId(), in.addressVersion().toString(), in.expectedTotalTwd().toString()));
        var prior = db.queryForList("SELECT id, fingerprint FROM checkout_group WHERE customer_id=? AND request_id=?", customer, in.requestId());
        if (!prior.isEmpty()) {
            if (!fingerprint.equals(prior.get(0).get("fingerprint"))) throw fail(HttpStatus.CONFLICT, "操作編號已用於不同內容，請核對訂單");
            return current(customer, (String) prior.get(0).get("id"), true);
        }
        Integer version = shoppingMapper.cartVersion(customer);
        if (version == null || version.intValue() != in.cartVersion()) throw fail(HttpStatus.CONFLICT, "購物車已變更，請重新核對");
        var cart = shopping.cart(customer);
        if (cart.items().isEmpty() && cart.preorders().isEmpty()) throw fail(HttpStatus.CONFLICT, "購物車是空的");
        if (!cart.checkoutReady()) throw fail(HttpStatus.CONFLICT, "購物車有商品無法購買，請先調整");

        // 固定上鎖順序：主檔 guard → 所有商品（排序）→ 子單內再鎖批次（依批次 ID 排序建立）
        masters.lock();
        var productIds = new TreeSet<String>();
        cart.items().forEach(i -> productIds.add(i.productId()));
        cart.preorders().forEach(p -> productIds.add(p.productId()));
        for (String id : productIds) catalog.lock(id);
        // 上鎖後重新計價：與畫面不同就停（不默默換價）
        var locked = shopping.cart(customer);
        long total = locked.totalTwd();
        if (!locked.checkoutReady()) throw fail(HttpStatus.CONFLICT, "庫存或配額已變更，請重新核對");
        if (total != in.expectedTotalTwd()) throw fail(HttpStatus.CONFLICT, "金額已變更，請重新核對後再下單");

        String groupId = UUID.randomUUID().toString();
        db.update("INSERT INTO checkout_group(id,customer_id,request_id,fingerprint,cart_version,address_id,address_version,expected_total_twd) VALUES(?,?,?,?,?,?,?,?)",
                groupId, customer, in.requestId(), fingerprint, in.cartVersion(), in.addressId(), in.addressVersion(), in.expectedTotalTwd());
        int position = 0;
        if (!locked.items().isEmpty()) {
            var wants = locked.items().stream().map(i -> new OrderService.Want(i.productId(), i.skuId(), i.quantity())).toList();
            var applied = orders.createForCheckout(customer, child(in.requestId(), "STOCK"), in.cartVersion(), in.addressId(), in.addressVersion(), locked.subtotalTwd(), wants);
            db.update("INSERT INTO checkout_group_order(group_id,position,stock_order_id) VALUES(?,?,?)", groupId, position++, applied.order().id());
        }
        var batches = new ArrayList<>(locked.preorders());
        batches.sort(Comparator.comparing(ShoppingPayloads.PreorderLine::batchId));
        for (var p : batches) {
            var applied = preorders.place(customer, new PreorderOrderPayloads.Place(child(in.requestId(), p.batchId()), p.batchId(), p.quantity(),
                    in.addressId(), in.addressVersion(), p.lineTotalTwd()));
            db.update("INSERT INTO checkout_group_order(group_id,position,preorder_order_id) VALUES(?,?,?)", groupId, position++, applied.order().id());
        }
        db.update("DELETE FROM customer_cart_preorder WHERE customer_id=?", customer);
        shoppingMapper.bumpCart(customer);
        return current(customer, groupId, false);
    }

    /** 本人結帳組的目前狀態（重送時也用這個，不會重新建立或復活已結束的子單）。 */
    @Transactional(readOnly = true)
    public Placed current(String customer, String groupId, boolean replayed) {
        var owner = db.queryForList("SELECT customer_id FROM checkout_group WHERE id=?", String.class, groupId);
        if (owner.isEmpty() || !owner.get(0).equals(customer)) throw fail(HttpStatus.NOT_FOUND, "找不到結帳紀錄");
        var placed = new ArrayList<PlacedOrder>();
        for (var row : db.queryForList("SELECT stock_order_id, preorder_order_id FROM checkout_group_order WHERE group_id=? ORDER BY position", groupId)) {
            String stock = (String) row.get("stock_order_id");
            if (stock != null) {
                OrderPayloads.View o = orders.detail(customer, stock);
                placed.add(new PlacedOrder("STOCK", o.id(), o.status(), o.totalTwd(), o.expiresAt() == null ? null : o.expiresAt().atOffset(TAIPEI),
                        o.items().isEmpty() ? "現貨" : o.items().get(0).title() + (o.items().size() > 1 ? " 等 " + o.items().size() + " 款" : "")));
            } else {
                var o = preorders.detail(customer, (String) row.get("preorder_order_id"));
                placed.add(new PlacedOrder("PREORDER", o.id(), o.status(), o.totalTwd(), o.expiresAt(), o.productTitle() + "・" + o.batchName()));
            }
        }
        return new Placed(groupId, placed, placed.stream().mapToLong(PlacedOrder::totalTwd).sum(), replayed);
    }

    /** 子單的 requestId：由結帳 requestId 決定性推導，各自的既有冪等機制照常運作。 */
    private static String child(String requestId, String part) {
        return UUID.nameUUIDFromBytes(("checkout|" + requestId + "|" + part).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
