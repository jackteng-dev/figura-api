package dev.jackteng.figura.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

/** Commerce V2 Phase 5：出貨單合併、私人倉庫暫存、運費差額審核。 */
public final class WarehousePayloads {
    private WarehousePayloads() {}
    static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    /** 顧客看到的出貨單＋可以做的操作。 */
    public record CustomerShipment(ShippingPayloads.Shipment shipment, boolean canHold, boolean canRelease, boolean mergeable, String blockedReason) {}
    public record CustomerPage(List<CustomerShipment> items, int page, int pageSize, long total, int storageDays) {}

    public record Ref(@NotBlank @Pattern(regexp = UUID) String id, @NotNull @Min(1) Integer expectedVersion) {}
    public record Merge(@NotBlank @Pattern(regexp = UUID) String requestId, @NotNull @Size(min = 2, max = 10) List<@Valid @NotNull Ref> shipments) {
        @JsonAnySetter public void unknown(String k, Object v) { throw new IllegalArgumentException("不支援的欄位"); }
    }
    public record HoldCommand(@NotBlank @Pattern(regexp = UUID) String requestId, @NotNull @Min(1) Integer expectedVersion) {
        @JsonAnySetter public void unknown(String k, Object v) { throw new IllegalArgumentException("不支援的欄位"); }
    }
    public record Adjustment(String id, String shipmentId, String customerId, int beforeFeeTwd, int afterFeeTwd, int creditTwd, String status, int version,
                             OffsetDateTime createdAt, OffsetDateTime decidedAt) {}
    /** 合併結果：新的出貨單、運費差額（沒有差額時為 null）、是否為重送。 */
    public record Merged(CustomerShipment shipment, Adjustment adjustment, boolean replayed) {}
    public record Held(CustomerShipment shipment, boolean replayed) {}

    // ---- 員工 ----
    public record WarehouseItem(ShippingPayloads.Shipment shipment, String customerId, boolean overdue) {}
    public record WarehousePage(List<WarehouseItem> items, int page, int pageSize, long total) {}
    public record StaffCommand(@NotBlank @Pattern(regexp = UUID) String requestId, @NotNull @Min(1) Integer expectedVersion,
                               @NotBlank @Size(max = 480) String note, @Min(1) @Max(180) Integer days) {
        @JsonAnySetter public void unknown(String k, Object v) { throw new IllegalArgumentException("不支援的欄位"); }
    }
    public record Decide(@NotBlank @Pattern(regexp = UUID) String requestId, @NotNull @Min(1) Integer expectedVersion,
                         @NotBlank @Pattern(regexp = "CREDIT_MOCK|DISMISS") String decision, @NotBlank @Size(max = 480) String note) {
        @JsonAnySetter public void unknown(String k, Object v) { throw new IllegalArgumentException("不支援的欄位"); }
    }
    public record OpsEvent(String requestId, String targetId, int actorId, String actorUsername, String action, String note, Integer days,
                           int beforeVersion, int afterVersion, OffsetDateTime createdAt) {}
    public record StaffApplied(OpsEvent event, boolean replayed) {}
    public record AdjustmentPage(List<Adjustment> items, int page, int pageSize, long total) {}
}
