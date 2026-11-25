package dev.jackteng.figura.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** 通知事件 → 顧客看得懂的標題與內文（站內與 Email 共用）。只用 payload 的安全欄位。 */
final class NoticeText {
    private NoticeText() {}
    private static final Map<String, String> TITLES = Map.ofEntries(
            Map.entry("ORDER_PLACED", "訂單已成立"), Map.entry("ORDER_CANCELLED", "訂單已取消"), Map.entry("ORDER_EXPIRED", "訂單逾時未付款"),
            Map.entry("PAYMENT_SUCCEEDED", "付款成功"), Map.entry("PAYMENT_FAILED", "付款未完成"), Map.entry("PAYMENT_REVIEW_REQUIRED", "付款待核對"),
            Map.entry("ORDER_SHIPPED", "訂單已出貨"), Map.entry("ORDER_DELIVERED", "訂單已送達"), Map.entry("ORDER_REFUNDED", "訂單已退款"),
            Map.entry("REVIEW_REFUNDED", "付款核對已退款"), Map.entry("RETURN_REQUESTED", "退貨申請已送出"), Map.entry("RETURN_RECEIVED", "退貨已收到"),
            Map.entry("RETURN_REJECTED", "退貨申請未通過"),
            Map.entry("PREORDER_PLACED", "預購已成立"), Map.entry("PREORDER_CANCELLED", "預購已取消"), Map.entry("PREORDER_EXPIRED", "預購逾時未付款"),
            Map.entry("PREORDER_PAYMENT_SUCCEEDED", "預購付款成功"), Map.entry("PREORDER_PAYMENT_FAILED", "預購付款未完成"),
            Map.entry("PREORDER_PAYMENT_REVIEW_REQUIRED", "預購付款待核對"), Map.entry("PREORDER_REFUNDED", "預購已退款"), Map.entry("PREORDER_REVIEW_REFUNDED", "預購付款核對已退款"),
            Map.entry("PREORDER_ALLOCATED", "預購商品已到貨"), Map.entry("PREORDER_SHIPPED", "預購商品已出貨"), Map.entry("PREORDER_DELIVERED", "預購商品已送達"),
            Map.entry("PREORDER_BALANCE_DUE", "預購尾款待付款"), Map.entry("PREORDER_DELAYED", "預購到貨時間延後"), Map.entry("PREORDER_RESCHEDULED", "預購時程已調整"),
            Map.entry("PRODUCT_RESTOCK", "商品補貨了"), Map.entry("PRODUCT_PREORDER_OPEN", "開放預購"), Map.entry("PRODUCT_PREORDER_CLOSING", "預購即將截止"),
            Map.entry("PRODUCT_RELEASE", "商品已到發售時間"));

    static boolean isProduct(String kind) { return kind.startsWith("PRODUCT_"); }

    /** @return {title, message} */
    static String[] of(String kind, JsonNode p) {
        String title = TITLES.getOrDefault(kind, "訂單通知");
        String name = p.path("title").asText("");
        String message = switch (kind) {
            case "PRODUCT_RESTOCK" -> "你關注的「" + name + "」現在有現貨。庫存有限，以下單時核對為準。";
            case "PRODUCT_PREORDER_OPEN" -> "你關注的「" + name + "」已開放預購" + deadline(p) + "。";
            case "PRODUCT_PREORDER_CLOSING" -> "「" + name + "」的預購即將截止" + deadline(p) + "。";
            case "PRODUCT_RELEASE" -> "「" + name + "」已到預計發售時間；實際供貨請以商品頁為準。";
            default -> {
                String id = p.path("orderId").asText("");
                yield "訂單 " + (id.length() >= 8 ? id.substring(0, 8).toUpperCase() : id) + "：" + title + "。詳情請到「我的訂單」查看。";
            }
        };
        return new String[]{title, message};
    }
    private static String deadline(JsonNode p) {
        String d = p.path("deadlineAt").asText("");
        return d.length() >= 16 ? "（截止 " + d.substring(0, 10) + " " + d.substring(11, 16) + "）" : "";
    }
}
