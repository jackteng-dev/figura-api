package dev.jackteng.figura.dto;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import dev.jackteng.figura.pojo.GalleryEvent;
import java.util.List;
public final class GalleryPayloads {
 private GalleryPayloads(){}
 public enum Type { MAIN,ANGLE,FACE,DETAIL,SCENE,REAR }
 public enum Action { REMOVE,COVER,REORDER }
 public record Upload(@NotBlank @Pattern(regexp=PaymentPayloads.UUID) String requestId,@NotNull @Min(0) Integer expectedVersion,@NotNull @Min(1) Integer expectedActorId,@NotBlank @Size(max=160) String alt,@NotNull Type type,@NotBlank @Pattern(regexp="ADD|REPLACE") String operation,@Pattern(regexp=PaymentPayloads.UUID) String imageId){}
 public record Command(@NotBlank @Pattern(regexp=PaymentPayloads.UUID) String requestId,@NotNull @Min(0) Integer expectedVersion,@NotNull @Min(1) Integer expectedActorId,@NotNull Action action,@Pattern(regexp=PaymentPayloads.UUID) String imageId,@Size(max=6) List<@NotBlank @Pattern(regexp=PaymentPayloads.UUID) String> imageIds){
  @JsonAnySetter public void unknown(String n,Object v){throw new IllegalArgumentException("不支援的欄位");}
 }
 public record Variant(String url,int width,int height,String contentType){}
 public record Image(String id,String url,String alt,int width,int height,Type type,int position,boolean primary,List<Variant> variants){}
 public record Audit(String requestId,String action,String imageId,int actorId,String actorUsername,int beforeVersion,int afterVersion,java.time.LocalDateTime createdAt){
  public static Audit from(GalleryEvent e){return new Audit(e.getRequestId(),e.getAction(),e.getImageId(),e.getActorId(),e.getActorUsername(),e.getBeforeVersion(),e.getAfterVersion(),e.getCreatedAt());}
 }
 public record Detail(boolean enabled,String productId,int version,String status,List<Image> images,List<Audit> events){}
 public record Applied(Audit event,Detail current,boolean replayed){}
}
