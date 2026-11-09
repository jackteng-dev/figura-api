package com.shop.elpsy.dto;

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
        @NotBlank @Pattern(regexp=UUID_PATTERN) String requestId, @NotBlank @Size(max=500) String note) {
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
    }
    public record Command(@NotNull @Min(0) Integer expectedVersion,
        @NotBlank @Pattern(regexp=UUID_PATTERN) String requestId,@NotBlank @Size(max=500) String note) {
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
    }
    public record Batch(String id,String productId,String skuId,String name,String description,String productTitle,String skuLabel,
        int priceTwd,int capacity,int heldQuantity,int paidQuantity,OffsetDateTime deadlineAt,LocalDate arrivalFrom,LocalDate arrivalTo,
        String status,int version,OffsetDateTime openedAt,OffsetDateTime closedAt) {}
    public record PublicBatch(String id,String productId,String name,String description,String productTitle,String skuLabel,
        int priceTwd,int availableQuota,OffsetDateTime deadlineAt,LocalDate arrivalFrom,LocalDate arrivalTo,String status,boolean orderingEnabled) {}
    public record Event(String requestId,String batchId,int actorId,String actorUsername,String action,
        @JsonIgnore String fingerprint,String beforeState,String afterState,String note,OffsetDateTime createdAt) {}
    public record Detail(Batch batch,List<Event> events) {}
    public record Applied(Event event,Batch current,boolean replayed) {}
}
