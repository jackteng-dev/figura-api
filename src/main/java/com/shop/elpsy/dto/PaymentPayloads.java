package com.shop.elpsy.dto;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;
public final class PaymentPayloads {
 private PaymentPayloads() {}
 public static final String UUID="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
 public record Start(@NotBlank @Pattern(regexp=UUID) String requestId,@NotNull @Min(1) Integer expectedOrderVersion){
  @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
 }
 public record Simulate(@NotBlank @Pattern(regexp=UUID) String eventId,@NotBlank @Pattern(regexp="SUCCESS|FAILURE") String outcome){
  @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
 }
 public record Notification(@NotBlank @Pattern(regexp=UUID) String eventId,@NotBlank @Pattern(regexp=UUID) String paymentId,
  @NotBlank @Pattern(regexp="mock_[0-9a-f-]{36}") String transactionId,@NotNull @Min(0) Long amountTwd,
  @NotBlank @Pattern(regexp="TWD") String currency,@NotBlank @Pattern(regexp="SUCCESS|FAILURE") String outcome){
  @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
 }
 public record View(String id,String orderId,String status,long amountTwd,String currency,String provider,LocalDateTime createdAt,LocalDateTime settledAt,RefundPayloads.Progress refund){}
 public record Options(boolean enabled,boolean canStart,List<View> items){}
 public record CallbackAck(String eventId,String disposition,boolean replayed,String paymentId,String orderId){}
 public record Started(View payment,boolean replayed){}
 public record Receipt(String eventId,String disposition,boolean replayed,View payment,OrderPayloads.View order){}
}
