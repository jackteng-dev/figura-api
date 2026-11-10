package dev.jackteng.figura.dto;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import dev.jackteng.figura.pojo.*;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;
public final class ImagePayloads {
 private ImagePayloads(){}
 public record Upload(@NotBlank @Pattern(regexp=PaymentPayloads.UUID) String requestId,@NotNull @Min(0) Integer expectedVersion,@NotBlank @Size(max=160) String alt){}
 public record Remove(@NotBlank @Pattern(regexp=PaymentPayloads.UUID) String requestId,@NotNull @Min(0) Integer expectedVersion){@JsonAnySetter public void unknown(String n,Object v){throw new IllegalArgumentException("不支援的欄位");}}
 public record Image(String id,String url,String alt,int width,int height){public static Image from(ProductImage i,boolean publicRead){return i==null?null:new Image(i.getId(),"/api/"+(publicRead?"catalog":"admin")+"/products/"+i.getProductId()+"/image/"+i.getId(),i.getAlt(),i.getWidth(),i.getHeight());}}
 public record Audit(String requestId,int actorId,String actorUsername,String action,String imageId,int beforeVersion,int afterVersion,LocalDateTime createdAt){public static Audit from(ImageEvent e){return new Audit(e.getRequestId(),e.getActorId(),e.getActorUsername(),e.getAction(),e.getImageId(),e.getBeforeVersion(),e.getAfterVersion(),e.getCreatedAt());}}
 public record Detail(boolean enabled,String productId,int version,String status,Image image,List<Audit> events){}
 public record Applied(Audit event,Detail current,boolean replayed){}
}
