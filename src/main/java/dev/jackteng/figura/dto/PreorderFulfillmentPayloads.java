package dev.jackteng.figura.dto;
import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import static dev.jackteng.figura.dto.PreorderPayloads.UUID_PATTERN;
public final class PreorderFulfillmentPayloads {
 private PreorderFulfillmentPayloads() {}
 public record Receive(@NotBlank @Pattern(regexp=UUID_PATTERN) String requestId,@NotNull @Min(1) Integer expectedBatchVersion,@NotNull @Min(1) @Max(100000) Integer quantity,@NotBlank @Size(max=500) String note){@JsonAnySetter public void unknown(String k,Object v){throw new IllegalArgumentException("不支援的欄位");}}
 public record Allocate(@NotBlank @Pattern(regexp=UUID_PATTERN) String requestId,@NotNull @Min(1) Integer expectedOrderVersion,@NotNull @Min(1) Integer expectedBatchVersion,@NotBlank @Size(max=500) String note){@JsonAnySetter public void unknown(String k,Object v){throw new IllegalArgumentException("不支援的欄位");}}
 public record Command(@NotBlank @Pattern(regexp=UUID_PATTERN) String requestId,@NotNull @Min(1) Integer expectedOrderVersion,@NotBlank @Size(max=500) String note){@JsonAnySetter public void unknown(String k,Object v){throw new IllegalArgumentException("不支援的欄位");}}
 public record Progress(int allocatedQuantity,OffsetDateTime allocatedAt,String provider,String trackingNumber,OffsetDateTime shippedAt,OffsetDateTime deliveredAt) {}
 public record Supply(String batchId,String batchName,String status,int version,int capacity,int receivedQuantity,int allocatedQuantity,int shippedQuantity,int unallocatedQuantity,int onHandQuantity) {}
 public record Event(String requestId,String batchId,String orderId,int actorId,String actorUsername,String action,@JsonIgnore String fingerprint,String note,int quantity,int receivedBefore,int receivedAfter,int allocatedBefore,int allocatedAfter,int shippedBefore,int shippedAfter,int batchVersionBefore,int batchVersionAfter,Integer orderVersionBefore,Integer orderVersionAfter,String trackingNumber,OffsetDateTime createdAt) {}
 public record BatchDetail(Supply supply,List<Event> events) {}
 public record Detail(Supply supply,boolean mockEnabled,boolean canAllocate,boolean canShip,boolean canDeliver,List<Event> events) {}
 public record Received(Event event,BatchDetail current,boolean replayed) {}
 public record Applied(Event event,PreorderOrderPayloads.View order,Detail current,boolean replayed) {}
}
