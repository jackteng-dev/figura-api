package com.shop.elpsy.dto;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;
public final class NotificationPayloads {
 private NotificationPayloads() {}
 public record Change(@NotBlank @Pattern(regexp=PaymentPayloads.UUID) String requestId,@NotNull @Min(0) Integer expectedVersion,@NotBlank @Size(max=500) String note){@JsonAnySetter public void unknown(String k,Object v){throw new IllegalArgumentException("不支援的欄位");}}
 public record Dispatch(@NotBlank @Pattern(regexp=PaymentPayloads.UUID) String requestId,@NotNull @Min(0) Integer expectedVersion,@NotBlank @Size(max=500) String note,@NotBlank @Pattern(regexp="SUCCESS|FAIL_BEFORE|FAIL_AFTER_ACCEPT") String mode){@JsonAnySetter public void unknown(String k,Object v){throw new IllegalArgumentException("不支援的欄位");}}
 public record Message(String id,String eventKind,String orderId,String channel,String status,int version,int attempts,int cycleAttempts,LocalDateTime nextAttemptAt,String lastError,LocalDateTime sentAt,LocalDateTime createdAt,boolean canDispatch,boolean canRetry,boolean mockAccepted) {}
 public record Audit(String requestId,String notificationId,Integer actorId,String actorUsername,String action,String mode,String note,int expectedVersion,String beforeStatus,String afterStatus,int afterVersion,int attemptNumber,String result,LocalDateTime createdAt) {}
 public record Detail(Message message,String snapshot,List<Audit> events,boolean mockEnabled) {}
 public record Applied(Audit event,Message current,boolean replayed) {}
}
