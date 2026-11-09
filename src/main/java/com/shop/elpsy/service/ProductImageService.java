package com.shop.elpsy.service;
import com.shop.elpsy.dto.ImagePayloads.*;
import com.shop.elpsy.mapper.*;
import com.shop.elpsy.pojo.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
@Service public class ProductImageService {
 private final ImageMapper images;private final CatalogMapper products;private final FulfillmentMapper staff;private final ImageObjectStore store;private final ImageDecoder decoder;private final ImageCleanupService cleanup;private final TransactionTemplate tx;private final boolean enabled;
 public ProductImageService(ImageMapper images,CatalogMapper products,FulfillmentMapper staff,ImageObjectStore store,ImageDecoder decoder,ImageCleanupService cleanup,PlatformTransactionManager manager,@Value("${shop.images.enabled:false}") boolean enabled){this.images=images;this.products=products;this.staff=staff;this.store=store;this.decoder=decoder;this.cleanup=cleanup;this.enabled=enabled;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
 private ResponseStatusException fail(HttpStatus s,String message){return new ResponseStatusException(s,message);}
 private void id(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(RuntimeException e){throw fail(HttpStatus.BAD_REQUEST,"商品或圖片編號不正確");}}
 private CatalogProduct require(CatalogProduct p){if(p==null)throw fail(HttpStatus.NOT_FOUND,"找不到商品圖片");return p;}
 private Detail view(CatalogProduct p){return new Detail(enabled,p.getId(),p.getVersion(),p.getStatus(),Image.from(images.cover(p.getId()),false),images.history(p.getId()).stream().map(Audit::from).toList());}
 @Transactional(readOnly=true) public Detail detail(String product){id(product);return view(require(products.find(product)));}
 public Applied upload(String product,Upload input,MultipartFile file,CustomUserDetails actor){if(!enabled)throw fail(HttpStatus.NOT_FOUND,"圖片儲存未啟用");id(product);var decoded=decoder.decode(file);return change(product,input.requestId(),input.expectedVersion(),input.alt().trim(),decoded,actor);}
 public Applied remove(String product,Remove input,CustomUserDetails actor){if(!enabled)throw fail(HttpStatus.NOT_FOUND,"圖片儲存未啟用");id(product);return change(product,input.requestId(),input.expectedVersion(),null,null,actor);}
 private Applied change(String product,String request,int version,String alt,ImageDecoder.Decoded decoded,CustomUserDetails actor){
  try{return tx.execute(s->{String action=decoded==null?"REMOVE":"UPLOAD";String username=staff.lockActor(actor.getId());if(username==null)throw fail(HttpStatus.UNAUTHORIZED,"員工登入狀態無效");if(staff.lockPermission(actor.getId(),decoded==null?"catalog.images.remove":"catalog.images.upload").isEmpty())throw fail(HttpStatus.FORBIDDEN,"沒有圖片操作權限");var p=require(products.lock(product));var prior=images.request(request);
   if(prior!=null){if(prior.getActorId()!=actor.getId()||!prior.getProductId().equals(product)||!prior.getAction().equals(action)||prior.getBeforeVersion()!=version||!Objects.equals(prior.getAlt(),alt)||!Objects.equals(prior.getSourceSha256(),decoded==null?null:decoded.sourceSha()))throw fail(HttpStatus.CONFLICT,"圖片操作編號已用於不同內容");return new Applied(Audit.from(prior),view(p),true);}
   if(p.getVersion()!=version)throw fail(HttpStatus.CONFLICT,"商品已被修改，請重新核對圖片");if(!"DRAFT".equals(p.getStatus()))throw fail(HttpStatus.CONFLICT,"圖片只能在草稿修改，請先下架");var old=images.cover(product);String image;
   if(decoded!=null){image=UUID.randomUUID().toString();String key="products/"+product+"/"+image+(decoded.type().equals("image/png")?".png":".jpg");cleanup.reserve(key);if(images.lockQueue(key)==null)throw fail(HttpStatus.CONFLICT,"圖片上傳保留已逾時，請重試");
    try{store.put(key,decoded.bytes(),decoded.type());}catch(RuntimeException e){throw fail(HttpStatus.SERVICE_UNAVAILABLE,"圖片儲存暫時無法使用，請重試同一操作");}
    var a=new ProductImage();a.setId(image);a.setProductId(product);a.setObjectKey(key);a.setSha256(decoded.sha());a.setSourceSha256(decoded.sourceSha());a.setContentType(decoded.type());a.setAlt(alt);a.setWidth(decoded.width());a.setHeight(decoded.height());a.setSizeBytes(decoded.bytes().length);images.insert(a);images.bind(product,image);images.unqueue(key);
   }else{if(old==null)throw fail(HttpStatus.CONFLICT,"此商品沒有可移除的主圖");image=old.getId();images.unbind(product);}
   if(old!=null)images.queue(old.getObjectKey());if(images.bump(product,version)!=1)throw fail(HttpStatus.CONFLICT,"商品已被修改");var event=new ImageEvent();event.setRequestId(request);event.setProductId(product);event.setActorId(actor.getId());event.setActorUsername(username);event.setAction(action);event.setImageId(image);event.setSourceSha256(decoded==null?null:decoded.sourceSha());event.setAlt(alt);event.setBeforeVersion(version);event.setAfterVersion(version+1);images.append(event);return new Applied(Audit.from(images.request(request)),view(require(products.find(product))),false);
  });}catch(DuplicateKeyException e){throw fail(HttpStatus.CONFLICT,"圖片操作編號已被使用，請重新核對");}
 }
 @Transactional(readOnly=true) public ProductImage current(String product,String image,boolean publicRead){id(product);id(image);require(publicRead?products.publicFind(product):products.find(product));var a=images.cover(product);if(a==null||!a.getId().equals(image))throw fail(HttpStatus.NOT_FOUND,"找不到商品圖片");return a;}
 public byte[] content(ProductImage image){try{byte[] bytes=store.get(image.getObjectKey());if(bytes.length!=image.getSizeBytes()||!ImageDecoder.sha(bytes).equals(image.getSha256()))throw new IllegalStateException("Image object mismatch");return bytes;}catch(RuntimeException e){throw fail(HttpStatus.SERVICE_UNAVAILABLE,"商品圖片暫時無法載入");}}
}
