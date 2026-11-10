package dev.jackteng.figura.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;
import java.util.List;
import java.time.LocalDateTime;

public final class AccessPayloads {
 private AccessPayloads() {}
 public record StaffCreate(@NotBlank @Pattern(regexp="[a-zA-Z0-9_]{3,64}") String username,
   @NotBlank @Size(min=12,max=72) String password, @NotNull Boolean enabled,
   @NotNull @Size(min=1,max=50) List<@NotNull @Positive Integer> roleIds,
   @NotBlank @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId,
   @NotBlank @Size(max=500) String note) {
   @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
   @Override public String toString(){return "StaffCreate[redacted]";}
 }
 public record StaffWrite(@NotNull Boolean enabled, @NotNull @Size(min=1,max=50) List<@NotNull @Positive Integer> roleIds,
   @NotNull @Min(0) Integer expectedVersion,
   @NotBlank @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId,
   @NotBlank @Size(max=500) String note) {
   @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
 }
 public record RoleWrite(@NotBlank @Size(max=64) String name, @NotNull @Size(max=255) String description,
   @NotNull Boolean enabled, @NotNull @Size(max=100) List<@NotNull @Positive Integer> permissionIds,
   @NotNull @Min(0) Integer expectedVersion,
   @NotBlank @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId,
   @NotBlank @Size(max=500) String note) {
   @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
 }
 public record Staff(int id,String username,boolean enabled,int version,List<Integer> roleIds) {}
 public record Role(int id,String name,String description,boolean enabled,int version,List<Integer> permissionIds) {}
 public record Capability(int id,String code,String name,String description) {}
 public record Event(String requestId,int actorId,String actorUsername,String action,int targetId,
   @JsonIgnore String fingerprint,String beforeState,String afterState,String note,LocalDateTime createdAt) {}
 public record Applied(Event event,boolean replayed) {}
}
