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
    private final ShippingService shipping;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public CheckoutService(ShoppingService shopping, ShoppingMapper shoppingMapper, CatalogMapper catalog, CatalogMasterService masters,
                           OrderService orders, PreorderOrderService preorders, JdbcTemplate db, ShippingService shipping,
                           com.fasterxml.jackson.databind.ObjectMapper json) {
        this.shopping = shopping; this.shoppingMapper = shoppingMapper; this.catalog = catalog; this.masters = masters;
        this.orders = orders; this.preorders = preorders; this.db = db; this.shipping = shipping; this.json = json;
    }
    private static ResponseStatusException fail(HttpStatus status, String message) { return new ResponseStatusException(status, message); }

    /** 結帳預覽：依購物車即時價格分組，不保留任何庫存或配額。 */
    @Transactional(readOnly = true)
    public Quote quote(String customer) { return quote(customer, "HOME", "SPLIT"); }
    @Transactional(readOnly = true)
    public Quote quote(String customer, String method, String strategy) { return quoteOf(shopping.cart(customer), method, strategy); }

    /** 分組＋運費：先依商品分組，再依配送策略決定出貨單與每張出貨單的運費（記在承運組）。 */
    Quote quoteOf(ShoppingPayloads.CartView cart, String methodCode, String strategy) {
        var base = groupsOf(cart);
        var method = shipping.method(methodCode);
        var methods = shipping.methods();
        // 只有一組時合併與分開相同
        String effective = base.size() > 1 ? strategy : "SPLIT";
        var shipments = new ArrayList<QuoteShipment>();
        var fees = new HashMap<String, Integer>();
        if (effective.equals("COMBINED")) {
            long goods = base.stream().mapToLong(QuoteGroup::subtotalTwd).sum();
            String carrier = base.get(0).key();  // 現貨組排在第一；沒有現貨時為第一個預購組
            int fee = ShippingService.fee(method, goods);
            shipments.add(new QuoteShipment("SHIPMENT:COMBINED", base.stream().map(QuoteGroup::key).toList(), carrier, goods, fee));
            fees.put(carrier, fee);
        } else for (var g : base) {
            int fee = ShippingService.fee(method, g.subtotalTwd());
            shipments.add(new QuoteShipment("SHIPMENT:" + g.key(), List.of(g.key()), g.key(), g.subtotalTwd(), fee));
            fees.put(g.key(), fee);
        }
        var groups = new ArrayList<QuoteGroup>();
        for (var g : base) {
            int fee = fees.getOrDefault(g.key(), 0);
            long total = g.subtotalTwd() + fee;
            // 運費跟著該訂單：全額／現貨現在付；訂金訂單加在尾款
            long now = "DEPOSIT".equals(g.paymentMode()) ? g.payNowTwd() : total;
            groups.add(new QuoteGroup(g.key(), g.kind(), g.title(), g.batchId(), g.arrivalFrom(), g.arrivalTo(), g.arrivalMonth(), g.deadlineAt(), g.lines(),
                    g.subtotalTwd(), fee, total, now, total - now, g.ready(), g.paymentMode()));
        }
        long subtotal = groups.stream().mapToLong(QuoteGroup::subtotalTwd).sum();
        int shippingTwd = groups.stream().mapToInt(QuoteGroup::shippingTwd).sum();
        long payNow = groups.stream().mapToLong(QuoteGroup::payNowTwd).sum(), payLater = groups.stream().mapToLong(QuoteGroup::payLaterTwd).sum();
        return new Quote(cart.version(), groups, subtotal, shippingTwd, subtotal + shippingTwd, payNow, payLater, cart.checkoutReady() && method.enabled(),
                method.code(), effective, shipments, methods, method.enabled());
    }

    static List<QuoteGroup> groupsOf(ShoppingPayloads.CartView cart) {
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
        return groups;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Placed place(String customer, Place in) {
        if (shoppingMapper.lockCustomer(customer) == null) throw fail(HttpStatus.UNAUTHORIZED, "顧客登入狀態無效");
        // 配送選擇也是內容的一部分；未送配送欄位（舊客戶端）時與 Phase 3 相同的 fingerprint
        boolean legacy = in.shippingMethod() == null && in.deliveryStrategy() == null && in.pickupStoreId() == null;
        String fingerprint = sha256(String.join("|", "CHECKOUT", in.cartVersion().toString(), in.addressId(), in.addressVersion().toString(), in.expectedTotalTwd().toString())
                + (legacy ? "" : "|" + in.method() + "|" + in.strategy() + "|" + Objects.toString(in.pickupStoreId(), "")));
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
        var pickup = in.method().equals("CVS") ? ShippingService.store(Objects.toString(in.pickupStoreId(), "")).orElseThrow(() -> fail(HttpStatus.BAD_REQUEST, "請選擇超商取貨門市"))
                : null;
        if (pickup == null && in.pickupStoreId() != null) throw fail(HttpStatus.BAD_REQUEST, "宅配不需要取貨門市");

        // 固定上鎖順序：主檔 guard → 所有商品（排序）→ 子單內再鎖批次（依批次 ID 排序建立）
        masters.lock();
        var productIds = new TreeSet<String>();
        cart.items().forEach(i -> productIds.add(i.productId()));
        cart.preorders().forEach(p -> productIds.add(p.productId()));
        for (String id : productIds) catalog.lock(id);
        // 上鎖後重新計價：與畫面不同就停（不默默換價）
        var locked = shopping.cart(customer);
        if (!locked.checkoutReady()) throw fail(HttpStatus.CONFLICT, "庫存或配額已變更，請重新核對");
        // 運費規則在交易內重讀：與畫面金額（含運費）不同就停
        var quote = quoteOf(locked, in.method(), in.strategy());
        if (!quote.shippingAvailable()) throw fail(HttpStatus.CONFLICT, "此配送方式暫停使用，請改選其他方式");
        if (quote.totalTwd() != in.expectedTotalTwd()) throw fail(HttpStatus.CONFLICT, "金額或運費已變更，請重新核對後再下單");
        var feeOf = new HashMap<String, Integer>();
        quote.groups().forEach(g -> feeOf.put(g.key(), g.shippingTwd()));

        String groupId = UUID.randomUUID().toString();
        db.update("INSERT INTO checkout_group(id,customer_id,request_id,fingerprint,cart_version,address_id,address_version,expected_total_twd,shipping_method,delivery_strategy,shipping_twd) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                groupId, customer, in.requestId(), fingerprint, in.cartVersion(), in.addressId(), in.addressVersion(), in.expectedTotalTwd(), quote.shippingMethod(), quote.deliveryStrategy(), quote.shippingTwd());
        int position = 0;
        var orderOf = new HashMap<String, String[]>();  // group key -> {kind, orderId}
        if (!locked.items().isEmpty()) {
            var wants = locked.items().stream().map(i -> new OrderService.Want(i.productId(), i.skuId(), i.quantity())).toList();
            var applied = orders.createForCheckout(customer, child(in.requestId(), "STOCK"), in.cartVersion(), in.addressId(), in.addressVersion(), locked.subtotalTwd(), wants, feeOf.getOrDefault("STOCK", 0));
            db.update("INSERT INTO checkout_group_order(group_id,position,stock_order_id) VALUES(?,?,?)", groupId, position++, applied.order().id());
            orderOf.put("STOCK", new String[]{"STOCK", applied.order().id()});
        }
        var batches = new ArrayList<>(locked.preorders());
        batches.sort(Comparator.comparing(ShoppingPayloads.PreorderLine::batchId));
        for (var p : batches) {
            var applied = preorders.place(customer, new PreorderOrderPayloads.Place(child(in.requestId(), p.batchId()), p.batchId(), p.quantity(),
                    in.addressId(), in.addressVersion(), p.lineTotalTwd()), feeOf.getOrDefault("PREORDER:" + p.batchId(), 0));
            db.update("INSERT INTO checkout_group_order(group_id,position,preorder_order_id) VALUES(?,?,?)", groupId, position++, applied.order().id());
            orderOf.put("PREORDER:" + p.batchId(), new String[]{"PREORDER", applied.order().id()});
        }
        // 出貨單：目的地快照（宅配地址或測試超商門市）；運費已記在承運訂單上
        var address = shoppingMapper.address(in.addressId());
        var destination = new LinkedHashMap<String, Object>();
        destination.put("type", pickup == null ? "HOME" : "CVS");
        destination.put("recipientName", address.getRecipientName()); destination.put("phone", address.getPhone());
        if (pickup == null) { destination.put("postalCode", address.getPostalCode()); destination.put("city", address.getCity()); destination.put("district", address.getDistrict()); destination.put("street", address.getStreet()); }
        else { destination.put("storeBrand", pickup.brandName()); destination.put("storeId", pickup.storeId()); destination.put("storeName", pickup.storeName()); destination.put("storeAddress", pickup.address()); destination.put("test", true); }
        String encoded;
        try { encoded = json.writeValueAsString(destination); } catch (Exception e) { throw new IllegalStateException(e); }
        for (var sh : quote.shipments()) {
            String shipmentId = UUID.randomUUID().toString();
            db.update("INSERT INTO shipment(id,customer_id,group_id,method,strategy,destination,fee_twd,goods_twd) VALUES(?,?,?,?,?,?,?,?)",
                    shipmentId, customer, groupId, quote.shippingMethod(), quote.deliveryStrategy(), encoded, sh.feeTwd(), sh.goodsTwd());
            int at = 0;
            for (String key : sh.groupKeys()) {
                var o = orderOf.get(key);
                db.update("INSERT INTO shipment_item(shipment_id,position," + (o[0].equals("STOCK") ? "stock_order_id" : "preorder_order_id") + ") VALUES(?,?,?)", shipmentId, at++, o[1]);
            }
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
