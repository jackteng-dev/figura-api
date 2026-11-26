package dev.jackteng.figura.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

/** Commerce V2 Phase 3：收藏、商品提醒、通知偏好與站內通知匣（顧客 Web 與 App 共用）。 */
public final class EngagementPayloads {
    private EngagementPayloads() {}
    public static final List<String> ALERT_KINDS = List.of("RESTOCK", "PREORDER_OPEN", "PREORDER_CLOSING", "RELEASE");
    public static final int MAX_FAVORITES = 500, MAX_ALERT_PRODUCTS = 200, MAX_MERGE = 100;

    public record Favorite(CatalogPayloads.PublicView product, LocalDateTime favoritedAt, List<String> alerts) {}
    public record FavoriteIds(List<String> productIds) {}
    public record Merge(@NotNull @Size(max = MAX_MERGE) List<@NotNull @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String> productIds) {}
    public record MergeResult(List<String> merged, List<String> skipped, int total) {}

    public record AlertSet(@NotNull @Size(max = 4) List<@NotNull String> kinds) {}
    public record ProductAlert(String productId, String title, List<String> kinds, String availability) {}

    public record Preferences(boolean emailEnabled, boolean orderUpdates, boolean productAlerts, boolean emailVerified, boolean emailDeliveryActive, int version) {}
    public record PreferencesWrite(@NotNull Boolean emailEnabled, @NotNull Boolean orderUpdates, @NotNull Boolean productAlerts, @NotNull @Min(0) Integer expectedVersion) {}

    /** 站內通知：只含安全欄位（無收件資料、員工備註）。 */
    public record Notice(String id, String kind, String category, String title, String message, String orderId, String preorderOrderId, String productId,
                         LocalDateTime createdAt, LocalDateTime readAt) {}
    public record Inbox(List<Notice> items, int page, int pageSize, long total, long unread) {}
    public record MarkRead(@Size(max = 100) List<@NotNull String> ids, Boolean all) {}
    public record ReadResult(int updated, long unread) {}
    public record ScanResult(int products, int events) {}
}
