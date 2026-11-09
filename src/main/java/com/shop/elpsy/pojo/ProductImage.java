package com.shop.elpsy.pojo;
import lombok.Data;
import java.time.LocalDateTime;
@Data public class ProductImage {
 private String id,productId,objectKey,sha256,sourceSha256,contentType,alt;
 private int width,height,sizeBytes;
 private LocalDateTime createdAt;
}
