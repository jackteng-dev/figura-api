package dev.jackteng.figura.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.time.*;
import static dev.jackteng.figura.dto.PreorderPayloads.UUID_PATTERN;

public final class PreorderOrderPayloads {
 private PreorderOrderPayloads() {}
 public record Place(@NotBlank @Pattern(regexp=UUID_PATTERN) String requestId,
  @NotBlank @Pattern(regexp=UUID_PATTERN) String batchId,
  @NotNull @Min(1) @Max(100) Integer quantity,
  @NotBlank @Pattern(regexp=UUID_PATTERN) String addressId,
  @NotNull @Min(1) Integer addressVersion,
  @NotNull @Min(1) Long expectedSubtotalTwd) {
  @JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("不支援的欄位");}
 }
 public record Cancel(@NotBlank @Pattern(regexp=UUID_PATTERN) String requestId,@NotNull @Min(1) Integer expectedVersion) {
  @JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("不支援的欄位");}
 }
 public record View(String id,String batchId,String batchName,String productTitle,String skuLabel,
  int unitPriceTwd,int quantity,long subtotalTwd,int shippingTwd,long totalTwd,OrderPayloads.Shipping shipping,
  OffsetDateTime deadlineAt,LocalDate arrivalFrom,LocalDate arrivalTo,String status,int version,
  OffsetDateTime createdAt,OffsetDateTime expiresAt,OffsetDateTime closedAt,OffsetDateTime paidAt,
  String arrivalStatus,String fulfillmentStatus,boolean paymentReviewRequired,PreorderMoneyPayloads.RefundProgress refund,PreorderFulfillmentPayloads.Progress fulfillment,
  // Phase 4：FULL 或 DEPOSIT；DEPOSIT 的 status=PAID 代表訂金已付、名額已確保，尾款另看 balanceStatus（NOT_DUE／DUE／PAID）
  String paymentMode,long depositTwd,long balanceTwd,String balanceStatus,OffsetDateTime balanceDueAt,OffsetDateTime balancePaidAt,
  long payNowTwd,java.util.List<PreorderPayloads.ScheduleNotice> schedule,
  // Phase 5：所屬出貨單（舊訂單沒有，為 null）
  ShippingPayloads.Shipment shipment) {}
 public record Applied(View order,boolean replayed) {}
}
