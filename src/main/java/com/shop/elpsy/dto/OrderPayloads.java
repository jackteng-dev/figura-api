package com.shop.elpsy.dto;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;
public final class OrderPayloads {
 private OrderPayloads() {}
 public record Place(
  @NotBlank @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId,
  @NotNull @Min(0) Integer cartVersion,
  @NotBlank @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String addressId,
  @NotNull @Min(1) Integer addressVersion,
  @NotNull @Min(0) Long expectedSubtotalTwd) {
  @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
 }
 public record Cancel(@NotNull @Min(1) Integer expectedVersion) {
  @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
 }
 public record Line(String skuId,String productId,String title,String label,int quantity,int unitPriceTwd,long lineTotalTwd) {}
 public record Shipping(String recipientName,String phone,String postalCode,String city,String district,String street) {}
 public record View(String id,String status,int version,long subtotalTwd,int shippingTwd,long totalTwd,
  Shipping shipping,List<Line> items,LocalDateTime createdAt,LocalDateTime expiresAt,LocalDateTime closedAt,LocalDateTime paidAt,FulfillmentPayloads.Progress fulfillment,RefundPayloads.Progress refund,ReturnPayloads.Progress returnProgress) {}
 public record Applied(View order,boolean replayed) {}
}
