package dev.jackteng.figura.pojo;
import lombok.Data;
import java.time.LocalDateTime;
@Data public class ImageEvent {
 private String requestId,productId,actorUsername,action,imageId,sourceSha256,alt;
 private int actorId,beforeVersion,afterVersion;
 private LocalDateTime createdAt;
}
