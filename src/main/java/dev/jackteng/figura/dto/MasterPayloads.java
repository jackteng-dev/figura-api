package dev.jackteng.figura.dto;
import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
public final class MasterPayloads {
 private MasterPayloads() {}
 public record Write(@NotBlank @Size(max=80) String name,@NotNull @Size(max=500) String description,
   @NotNull Boolean enabled,@NotNull @Min(0) Integer expectedVersion,
   @NotBlank @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId,
   @NotBlank @Size(max=500) String note) {
   @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
 }
 public record Master(String id,String kind,String name,String description,boolean enabled,int version) {}
 public record Event(String requestId,int actorId,String actorUsername,String kind,String action,String targetId,
   @JsonIgnore String fingerprint,String beforeState,String afterState,String note,LocalDateTime createdAt) {}
 public record Applied(Event event,Master current,boolean replayed) {}
}
