package com.shop.elpsy.controller;
import com.shop.elpsy.dto.ImagePayloads.*;
import com.shop.elpsy.pojo.*;
import com.shop.elpsy.service.ProductImageService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
@RestController public class ProductImageController {
 private final ProductImageService service;public ProductImageController(ProductImageService s){service=s;}
 @GetMapping("/admin/products/{id}/image") public Result<Detail> detail(@PathVariable String id){return Result.success(service.detail(id));}
 @PostMapping(value="/admin/products/{id}/image",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public Result<Applied> upload(@PathVariable String id,@Valid @ModelAttribute Upload input,@RequestPart("file") MultipartFile file,@AuthenticationPrincipal CustomUserDetails actor,HttpServletRequest request) throws Exception {
  for(String name:request.getParameterMap().keySet())if(!Set.of("requestId","expectedVersion","alt").contains(name)||request.getParameterValues(name).length!=1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"不支援的欄位");
  for(var part:request.getParts())if(!Set.of("requestId","expectedVersion","alt","file").contains(part.getName()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"不支援的欄位");
  return Result.success(service.upload(id,input,file,actor));
 }
 @DeleteMapping("/admin/products/{id}/image") public Result<Applied> remove(@PathVariable String id,@Valid @RequestBody Remove input,@AuthenticationPrincipal CustomUserDetails actor){return Result.success(service.remove(id,input,actor));}
 @GetMapping("/admin/products/{id}/image/{image}") public ResponseEntity<byte[]> admin(@PathVariable String id,@PathVariable String image){return content(id,image,false);}
 @GetMapping("/catalog/products/{id}/image/{image}") public ResponseEntity<byte[]> published(@PathVariable String id,@PathVariable String image){return content(id,image,true);}
 private ResponseEntity<byte[]> content(String id,String image,boolean publicRead){var a=service.current(id,image,publicRead);return ResponseEntity.ok().contentType(MediaType.parseMediaType(a.getContentType())).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(service.content(a));}
}
