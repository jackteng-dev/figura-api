package dev.jackteng.figura.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 統一結帳：一次確認建立現貨子單＋每個預購批次一張子單（同一個資料庫交易）。 */
public final class CheckoutPayloads {
    private CheckoutPayloads() {}
    static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    /** 分組報價中的一行。 */
    public record QuoteLine(String productId, String skuId, String batchId, String title, String label, int quantity, int unitPriceTwd, long lineTotalTwd, String issue) {}
    /**
     * 一個結帳分組＝一張子訂單。kind=STOCK（所有現貨合併一張）或 PREORDER（每個批次一張）。
     * payNowTwd／payLaterTwd 是這一組「下單後要付」與「之後才付」的金額（Phase 4 訂金時才會有 payLater）。
     */
    public record QuoteGroup(String key, String kind, String title, String batchId, LocalDate arrivalFrom, LocalDate arrivalTo, String arrivalMonth,
                             OffsetDateTime deadlineAt, List<QuoteLine> lines, long subtotalTwd, int shippingTwd, long totalTwd,
                             long payNowTwd, long payLaterTwd, boolean ready, String paymentMode) {}
    /** 結帳預覽：不保留庫存或配額。cartVersion 與 totalTwd 送出時要原樣帶回核對。 */
    /**
     * 一張出貨單（Phase 5）：SPLIT 每組一張；COMBINED 全部合成一張（現貨等預購到貨一起寄）。
     * 運費記在 carrierKey 那一組（合併時優先現貨組）；goodsTwd 達到免運門檻即免運。
     */
    public record QuoteShipment(String key, List<String> groupKeys, String carrierKey, long goodsTwd, int feeTwd) {}
    public record Quote(int cartVersion, List<QuoteGroup> groups, long subtotalTwd, int shippingTwd, long totalTwd, long payNowTwd, long payLaterTwd, boolean ready,
                        String shippingMethod, String deliveryStrategy, List<QuoteShipment> shipments, List<ShippingPayloads.Method> methods, boolean shippingAvailable) {}

    public record Place(@NotBlank @Pattern(regexp = UUID) String requestId,
                        @NotNull @Min(1) Integer cartVersion,
                        @NotBlank @Pattern(regexp = UUID) String addressId,
                        @NotNull @Min(1) Integer addressVersion,
                        @NotNull @Min(0) Long expectedTotalTwd,
                        // Phase 5：省略＝宅配、分開寄。超商取貨需選測試門市；金額（含運費）以 expectedTotalTwd 核對
                        @Pattern(regexp = "HOME|CVS") String shippingMethod,
                        @Pattern(regexp = "SPLIT|COMBINED") String deliveryStrategy,
                        @Size(max = 32) String pickupStoreId) {
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("不支援的欄位"); }
        public String method() { return shippingMethod == null ? "HOME" : shippingMethod; }
        public String strategy() { return deliveryStrategy == null ? "SPLIT" : deliveryStrategy; }
    }
    /** 建立結果中的一張子訂單（詳情與付款走各自既有 API）。 */
    public record PlacedOrder(String kind, String id, String status, long totalTwd, OffsetDateTime expiresAt, String title) {}
    public record Placed(String groupId, List<PlacedOrder> orders, long totalTwd, boolean replayed) {}
}
