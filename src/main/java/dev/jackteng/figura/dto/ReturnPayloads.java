package dev.jackteng.figura.dto;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import dev.jackteng.figura.pojo.*;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;
public final class ReturnPayloads {
 private ReturnPayloads(){}
 public record Request(@NotBlank @Pattern(regexp=PaymentPayloads.UUID) String requestId,@NotNull @Min(1) Integer expectedOrderVersion,@NotBlank @Size(max=500) String reason){@JsonAnySetter public void unknown(String n,Object v){throw new IllegalArgumentException("不支援的欄位");}}
 public record Receive(@NotBlank @Pattern(regexp=PaymentPayloads.UUID) String requestId,@NotNull @Min(1) Integer expectedOrderVersion,@NotBlank @Size(max=500) String note,@NotBlank @Pattern(regexp="RESTOCK|NO_RESTOCK") String disposition){@JsonAnySetter public void unknown(String n,Object v){throw new IllegalArgumentException("不支援的欄位");}}
 public record Progress(String requestId,String status,String provider,String reason,String disposition,LocalDateTime requestedAt,LocalDateTime receivedAt,LocalDateTime refundedAt,LocalDateTime rejectedAt){public static Progress from(OrderReturn r){return r==null?null:new Progress(r.getRequestId(),r.getStatus(),"MOCK",r.getReason(),r.getDisposition(),r.getCreatedAt(),r.getReceivedAt(),r.getRefundedAt(),r.getRejectedAt());}}
 public record Options(boolean enabled,boolean canRequest,OrderPayloads.View order){}
 public record Requested(OrderPayloads.View order,boolean replayed){}
 public record Audit(String requestId,String orderId,int actorId,String actorUsername,String action,String note,int beforeVersion,int afterVersion,String beforeStatus,String afterStatus,String disposition,LocalDateTime createdAt){public static Audit from(ReturnEvent e){return new Audit(e.getRequestId(),e.getOrderId(),e.getActorId(),e.getActorUsername(),e.getAction(),e.getNote(),e.getBeforeVersion(),e.getAfterVersion(),e.getBeforeStatus(),e.getAfterStatus(),e.getDisposition(),e.getCreatedAt());}}
 public record Detail(boolean mockEnabled,boolean canReceive,boolean canReject,boolean canRefund,Progress request,List<Audit> events){}
 public record Applied(Audit event,OrderPayloads.View order,Detail current,boolean replayed){}
}
