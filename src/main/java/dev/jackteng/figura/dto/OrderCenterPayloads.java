package dev.jackteng.figura.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 統一訂單中心：現貨與預購的唯讀摘要。詳情與操作仍走各自既有 API（/customer/orders/{id}、/customer/preorders/{id}）。 */
public final class OrderCenterPayloads {
    private OrderCenterPayloads() {}
    /** 訂單來源。 */
    public enum Kind { ALL, STOCK, PREORDER }
    /**
     * 顧客看得懂的訂單階段（由伺服器依原本的付款／到貨／履約狀態推導，不改任何原始狀態）：
     * 待付款、等待到貨（預購）、待出貨、已出貨、已完成、退貨處理中（現貨）、已取消（含逾時）、已退款、付款待核對。
     */
    public enum Stage { ALL, PENDING_PAYMENT, WAITING_ARRIVAL, TO_SHIP, SHIPPED, COMPLETED, RETURNING, CANCELLED, REFUNDED, REVIEW }
    public enum Sort { NEWEST, OLDEST, AMOUNT_DESC, AMOUNT_ASC }

    public record Item(String kind, String id, String stage, String status, OffsetDateTime createdAt, long totalTwd,
                       String title, int lineCount, int quantity, String productId, OffsetDateTime expiresAt,
                       LocalDate arrivalFrom, LocalDate arrivalTo, String arrivalStatus, String fulfillmentStatus,
                       boolean paymentReviewRequired,
                       // 預購訂金：DUE 表示已付訂金、待付尾款（此時 stage 為 PENDING_PAYMENT）；現貨為 null
                       String balanceStatus, OffsetDateTime balanceDueAt) {}
    /** total／分頁依目前 kind＋stage 篩選後全域計算；counts 是目前 kind 下各階段的筆數（給分頁籤顯示）。 */
    public record Page(List<Item> items, int page, int pageSize, long total, Map<String, Long> counts) {}
}
