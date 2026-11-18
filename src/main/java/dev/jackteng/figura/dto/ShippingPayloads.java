package dev.jackteng.figura.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import static dev.jackteng.figura.dto.PreorderPayloads.UUID_PATTERN;

/** Phase 5 配送：配送方式與運費、超商測試門市、出貨單（測試物流，不是實際寄送）。 */
public final class ShippingPayloads {
    private ShippingPayloads() {}
    public record Method(String code, String name, int feeTwd, Integer freeThresholdTwd, boolean enabled, int version) {}
    public record MethodWrite(@NotBlank @Pattern(regexp = UUID_PATTERN) String requestId, @NotNull @Min(1) Integer expectedVersion,
                              @NotNull @Min(0) @Max(100000) Integer feeTwd, @Min(1) @Max(99999999) Integer freeThresholdTwd,
                              @NotNull Boolean enabled, @NotBlank @Size(max = 500) String note) {
        @JsonAnySetter public void unknown(String key, Object value) { throw new IllegalArgumentException("不支援的欄位"); }
    }
    public record MethodApplied(Method current, boolean replayed) {}
    /** 測試門市：只供開發與驗收，不是真實超商門市資料。 */
    public record PickupStore(String brand, String brandName, String storeId, String storeName, String address) {}
    /** 出貨單內的一張訂單（顧客只看得到自己的）。 */
    public record ShipmentOrder(String kind, String id, String title, String status, String fulfillmentStatus) {}
    /**
     * status 由訂單狀態推導：WAITING（等付款／到貨）、READY（可出貨）、SHIPPED、DELIVERED、CANCELLED（全部訂單已結束）。
     * carrier 一律 TEST、trackingNumber 為測試貨件號，不代表實際物流。
     */
    public record Shipment(String id, String method, String methodName, String strategy, int feeTwd, long goodsTwd, String status, String waitingFor,
                           String carrier, String trackingNumber, OffsetDateTime shippedAt, OffsetDateTime deliveredAt,
                           Map<String, Object> destination, List<ShipmentOrder> orders, int version, OffsetDateTime createdAt) {}
    public record Event(String requestId, String shipmentId, int actorId, String actorUsername, String action, String note,
                        int beforeVersion, int afterVersion, String trackingNumber, OffsetDateTime createdAt) {}
    public record Detail(Shipment shipment, boolean mockEnabled, boolean canShip, boolean canDeliver, List<Event> events) {}
    /** waiveUnpaidFee：承擔運費的訂單已結束、運費未收時，店長明確決定免收才可出貨（寫入稽核原因）。 */
    public record Command(@NotBlank @Pattern(regexp = UUID_PATTERN) String requestId, @NotNull @Min(1) Integer expectedVersion,
                          @NotBlank @Size(max = 480) String note, Boolean waiveUnpaidFee) {
        @JsonAnySetter public void unknown(String key, Object value) { throw new IllegalArgumentException("不支援的欄位"); }
    }
    public record Applied(Event event, Detail current, boolean replayed) {}
    public record Page(List<Shipment> items, int page, int pageSize, long total) {}
}
