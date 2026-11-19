package dev.jackteng.figura.service;

import dev.jackteng.figura.dto.GalleryPayloads.*;
import dev.jackteng.figura.mapper.*;
import dev.jackteng.figura.pojo.*;
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
import java.nio.charset.StandardCharsets;
import java.awt.image.BufferedImage;
import java.awt.RenderingHints;
import java.io.*;
import javax.imageio.ImageIO;

@Service public class ProductGalleryService {
 private final GalleryMapper gallery; private final ImageMapper images; private final CatalogMapper products;
 private final FulfillmentMapper staff; private final ImageObjectStore store; private final ImageDecoder decoder;
 private final ImageCleanupService cleanup; private final TransactionTemplate tx; private final boolean enabled;
 public ProductGalleryService(GalleryMapper gallery,ImageMapper images,CatalogMapper products,FulfillmentMapper staff,ImageObjectStore store,ImageDecoder decoder,ImageCleanupService cleanup,PlatformTransactionManager manager,@Value("${shop.images.enabled:false}") boolean enabled){
  this.gallery=gallery;this.images=images;this.products=products;this.staff=staff;this.store=store;this.decoder=decoder;this.cleanup=cleanup;this.enabled=enabled;
  tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
 }
 private ResponseStatusException fail(HttpStatus status,String message){return new ResponseStatusException(status,message);}
 private void id(String value){try{if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException();}catch(RuntimeException e){throw fail(HttpStatus.BAD_REQUEST,"商品或圖片編號不正確");}}
 private CatalogProduct require(CatalogProduct p){if(p==null)throw fail(HttpStatus.NOT_FOUND,"找不到商品圖片");return p;}
 private List<GalleryMapper.Entry> entries(String product){
  var all=new ArrayList<>(gallery.entries(product));var cover=images.cover(product);
  if(cover!=null&&all.stream().noneMatch(e->e.imageId().equals(cover.getId())))all.add(0,new GalleryMapper.Entry(cover.getId(),"MAIN",0));
  return all;
 }
 private String url(String product,String image,boolean publicRead){return "/api/"+(publicRead?"catalog":"admin")+"/products/"+product+"/image/"+image;}
 private List<Image> list(String product,boolean publicRead){
  var cover=images.cover(product);var result=new ArrayList<Image>();int position=0;
  for(var entry:entries(product)){
   var a=images.asset(entry.imageId());String url=url(product,a.getId(),publicRead);
   var variants=gallery.variants(a.getId()).stream().map(v->new Variant(url+"?width="+v.getWidth(),v.getWidth(),v.getHeight(),v.getContentType())).toList();
   result.add(new Image(a.getId(),url,a.getAlt(),a.getWidth(),a.getHeight(),Type.valueOf(entry.imageType()),position++,cover!=null&&cover.getId().equals(a.getId()),variants));
  }
  return result;
 }
 private Detail view(CatalogProduct p){
  var events=new ArrayList<Audit>(gallery.history(p.getId()).stream().map(Audit::from).toList());
  for(var e:images.history(p.getId()))events.add(new Audit(e.getRequestId(),e.getAction(),e.getImageId(),e.getActorId(),e.getActorUsername(),e.getBeforeVersion(),e.getAfterVersion(),e.getCreatedAt()));
  events.sort(Comparator.comparing(Audit::createdAt).reversed().thenComparing(Audit::requestId));
  return new Detail(enabled,p.getId(),p.getVersion(),p.getStatus(),list(p.getId(),false),events.stream().limit(50).toList());
 }
 @Transactional(readOnly=true) public Detail detail(String product){id(product);return view(require(products.find(product)));}
 @Transactional(readOnly=true) public List<Image> published(String product){id(product);require(products.publicFind(product));return list(product,true);}
 public Applied upload(String product,Upload input,MultipartFile file,CustomUserDetails actor){
  if(!enabled)throw fail(HttpStatus.NOT_FOUND,"圖片儲存未啟用");
  if((input.operation().equals("ADD")&&input.imageId()!=null)||(input.operation().equals("REPLACE")&&input.imageId()==null))throw fail(HttpStatus.BAD_REQUEST,"替換需指定圖片；新增不可指定圖片");
  return change(product,input.requestId(),input.expectedVersion(),input.expectedActorId(),input.operation(),input.imageId(),null,input.type(),input.alt().trim(),decoder.decode(file),actor);
 }
 public Applied command(String product,Command input,CustomUserDetails actor){
  if(input.action()==Action.REORDER?(input.imageId()!=null||input.imageIds()==null||input.imageIds().isEmpty()):(input.imageId()==null||input.imageIds()!=null))throw fail(HttpStatus.BAD_REQUEST,"圖片操作欄位不正確");
  return change(product,input.requestId(),input.expectedVersion(),input.expectedActorId(),input.action().name(),input.imageId(),input.imageIds(),null,null,null,actor);
 }
 private Applied change(String product,String request,int version,int expectedActor,String action,String target,List<String> order,Type type,String alt,ImageDecoder.Decoded decoded,CustomUserDetails actor){
  if(!enabled)throw fail(HttpStatus.NOT_FOUND,"圖片儲存未啟用");id(product);
  String fingerprint=ImageDecoder.sha(Arrays.asList(action,target,order,type,alt,decoded==null?null:decoded.sourceSha()).toString().getBytes(StandardCharsets.UTF_8));
  try{return tx.execute(status->{
   if(actor==null||actor.getId()!=expectedActor)throw fail(HttpStatus.CONFLICT,"登入員工已變更，請使用原員工核對此操作");
   String username=staff.lockActor(actor.getId());if(username==null)throw fail(HttpStatus.UNAUTHORIZED,"員工登入狀態無效");
   String permission=action.equals("REMOVE")?"catalog.images.remove":"catalog.images.upload";
   if(staff.lockPermission(actor.getId(),permission).isEmpty())throw fail(HttpStatus.FORBIDDEN,"沒有圖片操作權限");
   var p=require(products.lock(product));var prior=gallery.request(request);
   if(prior!=null){if(prior.getActorId()!=actor.getId()||!prior.getProductId().equals(product)||prior.getBeforeVersion()!=version||!prior.getFingerprint().equals(fingerprint))throw fail(HttpStatus.CONFLICT,"圖片操作編號已用於不同內容");return new Applied(Audit.from(prior),view(p),true);}
   if(images.request(request)!=null)throw fail(HttpStatus.CONFLICT,"圖片操作編號已被使用");gallery.reserve(request,product);
   if(version!=p.getVersion())throw fail(HttpStatus.CONFLICT,"商品已被修改，請重新核對圖片");
   if(!p.getStatus().equals("DRAFT"))throw fail(HttpStatus.CONFLICT,"圖片只能在草稿修改，請先下架");
   // Materialize the old cover lazily; no bulk rewrite of existing products.
   var cover=images.cover(product);var existing=entries(product);
   if(cover!=null&&gallery.entries(product).stream().noneMatch(e->e.imageId().equals(cover.getId())))gallery.add(product,cover.getId(),"MAIN",0);
   var selected=existing.stream().filter(e->e.imageId().equals(target)).findFirst();
   if(!action.equals("ADD")&&!action.equals("REORDER")&&selected.isEmpty())throw fail(HttpStatus.NOT_FOUND,"圖片不屬於此商品圖組");
   String resultId=target;
   if(decoded!=null){
    if(action.equals("ADD")&&existing.size()>=6)throw fail(HttpStatus.CONFLICT,"每件商品最多六張圖片");
    var asset=save(product,alt,decoded);resultId=asset.getId();
    int position=action.equals("REPLACE")?selected.orElseThrow().position():existing.stream().mapToInt(GalleryMapper.Entry::position).max().orElse(-1)+1;
    if(action.equals("REPLACE")){gallery.remove(product,target);queue(target);}
    gallery.add(product,resultId,type.name(),position);
    if(cover==null||cover.getId().equals(target))images.bind(product,resultId);
   }else if(action.equals("REMOVE")){
    gallery.remove(product,target);queue(target);
    if(cover!=null&&cover.getId().equals(target)){images.unbind(product);var remaining=gallery.entries(product);if(!remaining.isEmpty())images.bind(product,remaining.get(0).imageId());}
   }else if(action.equals("COVER")){images.bind(product,target);
   }else if(action.equals("REORDER")){
    var expected=new HashSet<>(existing.stream().map(GalleryMapper.Entry::imageId).toList());
    if(order.size()!=expected.size()||!new HashSet<>(order).equals(expected))throw fail(HttpStatus.CONFLICT,"排序必須包含每張目前圖片且不可重複");
    for(int i=0;i<order.size();i++)gallery.position(product,order.get(i),i);
   }
   if(images.bump(product,version)!=1)throw fail(HttpStatus.CONFLICT,"商品已被修改");
   var event=new GalleryEvent();event.setRequestId(request);event.setProductId(product);event.setActorId(actor.getId());event.setActorUsername(username);event.setAction(action);event.setImageId(resultId);event.setFingerprint(fingerprint);event.setBeforeVersion(version);event.setAfterVersion(version+1);gallery.append(event);
   return new Applied(Audit.from(gallery.request(request)),view(require(products.find(product))),false);
  });}catch(DuplicateKeyException e){throw fail(HttpStatus.CONFLICT,"圖片操作編號已被使用，請重新核對");}
 }
 private ProductImage save(String product,String alt,ImageDecoder.Decoded decoded){
  String image=UUID.randomUUID().toString(),key="products/"+product+"/"+image+(decoded.type().equals("image/png")?".png":".jpg");
  put(key,decoded.bytes(),decoded.type());var asset=new ProductImage();asset.setId(image);asset.setProductId(product);asset.setObjectKey(key);asset.setSourceSha256(decoded.sourceSha());asset.setSha256(decoded.sha());asset.setContentType(decoded.type());asset.setAlt(alt);asset.setWidth(decoded.width());asset.setHeight(decoded.height());asset.setSizeBytes(decoded.bytes().length);images.insert(asset);images.unqueue(key);
  try{var source=ImageIO.read(new ByteArrayInputStream(decoded.bytes()));
   for(int width:new int[]{320,640,1024}){if(width>=source.getWidth())continue;int height=Math.max(1,(int)Math.round((double)source.getHeight()*width/source.getWidth()));
    var small=new BufferedImage(width,height,source.getColorModel().hasAlpha()?BufferedImage.TYPE_INT_ARGB:BufferedImage.TYPE_INT_RGB);var g=small.createGraphics();try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(source,0,0,width,height,null);}finally{g.dispose();}
    var out=new ByteArrayOutputStream();String format=decoded.type().equals("image/png")?"png":"jpeg";if(!ImageIO.write(small,format,out))throw new IOException("No encoder");byte[] bytes=out.toByteArray();if(bytes.length>5242880)throw new IOException("Variant too large");String variantKey="products/"+product+"/"+image+"-"+width+(format.equals("png")?".png":".jpg");put(variantKey,bytes,decoded.type());
    var v=new ProductImage();v.setId(image);v.setWidth(width);v.setHeight(height);v.setObjectKey(variantKey);v.setSha256(ImageDecoder.sha(bytes));v.setContentType(decoded.type());v.setSizeBytes(bytes.length);gallery.variant(v);images.unqueue(variantKey);
   }
  }catch(IOException e){throw fail(HttpStatus.BAD_REQUEST,"圖片衍生處理失敗");}
  return asset;
 }
 private void put(String key,byte[] bytes,String type){cleanup.reserve(key);if(images.lockQueue(key)==null)throw fail(HttpStatus.CONFLICT,"圖片上傳保留已逾時");try{store.put(key,bytes,type);}catch(RuntimeException e){throw fail(HttpStatus.SERVICE_UNAVAILABLE,"圖片儲存暫時無法使用，請重試同一操作");}}
 private void queue(String image){images.queue(images.asset(image).getObjectKey());for(var v:gallery.variants(image))images.queue(v.getObjectKey());}
 @Transactional(readOnly=true) public ProductImage current(String product,String image,boolean publicRead,Integer width){
  id(product);id(image);require(publicRead?products.publicFind(product):products.find(product));
  if(entries(product).stream().noneMatch(e->e.imageId().equals(image)))throw fail(HttpStatus.NOT_FOUND,"找不到商品圖片");
  var asset=images.asset(image);if(width==null)return asset;
  return gallery.variants(image).stream().filter(v->v.getWidth()==width).findFirst().orElseThrow(()->fail(HttpStatus.NOT_FOUND,"找不到圖片尺寸"));
 }
}
