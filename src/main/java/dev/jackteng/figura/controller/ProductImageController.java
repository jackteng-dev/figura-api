package dev.jackteng.figura.controller;
import dev.jackteng.figura.dto.ImagePayloads.*;
import dev.jackteng.figura.pojo.*;
import dev.jackteng.figura.service.ProductImageService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
@RestController public class ProductImageController {
 private final jakarta.validation.Validator validator;private final ProductImageService service;private final dev.jackteng.figura.service.ProductGalleryService gallery;public ProductImageController(ProductImageService s,dev.jackteng.figura.service.ProductGalleryService gallery,jakarta.validation.Validator validator){service=s;this.gallery=gallery;this.validator=validator;}
 @GetMapping("/admin/products/{id}/image") public Result<?> detail(@PathVariable String id,@RequestParam(defaultValue="false") boolean gallery){return Result.success(gallery?this.gallery.detail(id):service.detail(id));}
 @PostMapping(value="/admin/products/{id}/image",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public Result<?> upload(@PathVariable String id,@Valid @ModelAttribute Upload input,@RequestPart("file") MultipartFile file,@AuthenticationPrincipal CustomUserDetails actor,HttpServletRequest request) throws Exception {
  for(String name:request.getParameterMap().keySet())if(!Set.of("requestId","expectedVersion","alt","type","operation","imageId","expectedActorId").contains(name)||request.getParameterValues(name).length!=1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"不支援的欄位");
  var multipart=org.springframework.web.util.WebUtils.getNativeRequest(request,org.springframework.web.multipart.MultipartHttpServletRequest.class);
  if(multipart==null||multipart.getMultiFileMap().size()!=1||multipart.getFiles("file").size()!=1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"只接受一個圖片檔案");
  for(var part:request.getParts())if(!Set.of("requestId","expectedVersion","alt","file","type","operation","imageId","expectedActorId").contains(part.getName()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"不支援的欄位");
  if(request.getParameter("operation")!=null){
   dev.jackteng.figura.dto.GalleryPayloads.Type imageType;try{imageType=dev.jackteng.figura.dto.GalleryPayloads.Type.valueOf(request.getParameter("type"));}catch(RuntimeException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"圖片類型不正確");}
   Integer expectedActor;try{expectedActor=Integer.valueOf(request.getParameter("expectedActorId"));}catch(RuntimeException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"員工核對欄位不正確");}
   var value=new dev.jackteng.figura.dto.GalleryPayloads.Upload(input.requestId(),input.expectedVersion(),expectedActor,input.alt(),imageType,request.getParameter("operation"),request.getParameter("imageId"));
   if(!validator.validate(value).isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"圖片欄位不正確");
   return Result.success(gallery.upload(id,value,file,actor));
  }
  if(request.getParameter("type")!=null||request.getParameter("imageId")!=null||request.getParameter("expectedActorId")!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"圖片欄位不正確");
  return Result.success(service.upload(id,input,file,actor));
 }
 @DeleteMapping(value="/admin/products/{id}/image",params="!gallery") public Result<Applied> remove(@PathVariable String id,@Valid @RequestBody Remove input,@AuthenticationPrincipal CustomUserDetails actor){return Result.success(service.remove(id,input,actor));}
 @DeleteMapping(value="/admin/products/{id}/image",params="gallery=true") public Result<?> removeGallery(@PathVariable String id,@Valid @RequestBody dev.jackteng.figura.dto.GalleryPayloads.Command input,@AuthenticationPrincipal CustomUserDetails actor){if(input.action()!=dev.jackteng.figura.dto.GalleryPayloads.Action.REMOVE)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"只接受移除操作");return Result.success(gallery.command(id,input,actor));}
 @GetMapping("/admin/products/{id}/image/{image}") public ResponseEntity<byte[]> admin(@PathVariable String id,@PathVariable String image,@RequestParam(required=false) Integer width){return content(id,image,false,width);}
 @GetMapping("/catalog/products/{id}/image/{image}") public ResponseEntity<byte[]> published(@PathVariable String id,@PathVariable String image,@RequestParam(required=false) Integer width){return content(id,image,true,width);}
 @GetMapping("/catalog/products/{id}/image") public Result<?> publishedGallery(@PathVariable String id){return Result.success(gallery.published(id));}
 @PostMapping(value="/admin/products/{id}/image",consumes=MediaType.APPLICATION_JSON_VALUE) public Result<?> command(@PathVariable String id,@Valid @RequestBody dev.jackteng.figura.dto.GalleryPayloads.Command input,@AuthenticationPrincipal CustomUserDetails actor){if(input.action()==dev.jackteng.figura.dto.GalleryPayloads.Action.REMOVE)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"移除請使用 DELETE");return Result.success(gallery.command(id,input,actor));}
 private ResponseEntity<byte[]> content(String id,String image,boolean publicRead,Integer width){var a=gallery.current(id,image,publicRead,width);return ResponseEntity.ok().contentType(MediaType.parseMediaType(a.getContentType())).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(service.content(a));}
}
