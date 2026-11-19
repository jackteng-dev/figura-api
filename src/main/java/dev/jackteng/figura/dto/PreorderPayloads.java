package dev.jackteng.figura.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.List;

public final class PreorderPayloads {
    private PreorderPayloads() {}
    public static final String UUID_PATTERN="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    public record Write(
        @NotBlank @Pattern(regexp=UUID_PATTERN) String productId,
        @NotBlank @Pattern(regexp=UUID_PATTERN) String skuId,
        @NotBlank @Size(max=120) String name, @NotNull @Size(max=2000) String description,
        @NotNull @Min(1) @Max(99999999) Integer priceTwd,
        @NotNull @Min(1) @Max(100000) Integer capacity,
        @NotNull OffsetDateTime deadlineAt, @NotNull LocalDate arrivalFrom, @NotNull LocalDate arrivalTo,
        @NotNull @Min(0) Integer expectedVersion,
        @NotBlank @Pattern(regexp=UUID_PATTERN) String requestId, @NotBlank @Size(max=500) String note,
        // Phase 4 付款條件：省略＝全額。DEPOSIT 需每件訂金（小於單價）與到貨通知後的尾款天數。
        @Pattern(regexp="FULL|DEPOSIT") String paymentMode, @Min(1) @Max(99999998) Integer depositTwd, @Min(1) @Max(60) Integer balanceDays) {
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
        public String mode(){return paymentMode==null?"FULL":paymentMode;}
    }
    /** 延期／調整預估到貨。publicReason 會顯示給顧客，note 只給員工稽核。 */
    public record Schedule(@NotNull @Min(0) Integer expectedVersion,@NotBlank @Pattern(regexp=UUID_PATTERN) String requestId,
        @NotNull LocalDate arrivalFrom,@NotNull LocalDate arrivalTo,@NotBlank @Size(max=200) String publicReason,@NotBlank @Size(max=500) String note) {
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
    }
    /** 到貨後通知訂金訂單支付尾款。 */
    public record BalanceCall(@NotNull @Min(0) Integer expectedVersion,@NotBlank @Pattern(regexp=UUID_PATTERN) String requestId,
        @NotBlank @Size(max=200) String publicReason,@NotBlank @Size(max=500) String note) {
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
    }
    public record Command(@NotNull @Min(0) Integer expectedVersion,
        @NotBlank @Pattern(regexp=UUID_PATTERN) String requestId,@NotBlank @Size(max=500) String note) {
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
    }
    public record Batch(String id,String productId,String skuId,String name,String description,String productTitle,String skuLabel,
        int priceTwd,int capacity,int heldQuantity,int paidQuantity,OffsetDateTime deadlineAt,LocalDate arrivalFrom,LocalDate arrivalTo,
        String status,int version,OffsetDateTime openedAt,OffsetDateTime closedAt,
        String paymentMode,Integer depositTwd,Integer balanceDays,OffsetDateTime balanceCalledAt,int receivedQuantity) {}
    public record PublicBatch(String id,String productId,String name,String description,String productTitle,String skuLabel,
        int priceTwd,int availableQuota,OffsetDateTime deadlineAt,LocalDate arrivalFrom,LocalDate arrivalTo,String status,boolean orderingEnabled,
        String paymentMode,Integer depositTwd,Integer balanceDays) {}
    public record Event(String requestId,String batchId,int actorId,String actorUsername,String action,
        @JsonIgnore String fingerprint,String beforeState,String afterState,String note,OffsetDateTime createdAt) {}
    /** 員工稽核用的時程／尾款通知紀錄。 */
    public record ScheduleEvent(String requestId,String batchId,int actorId,String actorUsername,String action,@JsonIgnore String fingerprint,
        String publicReason,String note,LocalDate arrivalFromBefore,LocalDate arrivalToBefore,LocalDate arrivalFromAfter,LocalDate arrivalToAfter,
        OffsetDateTime balanceDueAt,int affectedOrders,int batchVersionBefore,int batchVersionAfter,OffsetDateTime createdAt) {}
    /** 顧客看得到的時程異動（不含員工、內部說明）。 */
    public record ScheduleNotice(String action,String publicReason,LocalDate arrivalFromBefore,LocalDate arrivalToBefore,
        LocalDate arrivalFromAfter,LocalDate arrivalToAfter,OffsetDateTime createdAt) {}
    public record Detail(Batch batch,List<Event> events,List<ScheduleEvent> schedules) {}
    public record ScheduleApplied(ScheduleEvent event,Batch current,boolean replayed) {}
    public record Applied(Event event,Batch current,boolean replayed) {}
}
