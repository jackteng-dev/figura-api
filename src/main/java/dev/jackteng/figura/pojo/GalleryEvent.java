package dev.jackteng.figura.pojo;
import lombok.Data;
import java.time.LocalDateTime;
@Data public class GalleryEvent {
 private String requestId,productId,actorUsername,action,imageId,fingerprint;
 private int actorId,beforeVersion,afterVersion;
 private LocalDateTime createdAt;
}
