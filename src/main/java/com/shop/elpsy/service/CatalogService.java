package com.shop.elpsy.service;
import com.shop.elpsy.dto.CatalogPayloads.*;
import com.shop.elpsy.mapper.CatalogMapper;
import com.shop.elpsy.mapper.InventoryMapper;
import com.shop.elpsy.pojo.CatalogProduct;
import com.shop.elpsy.pojo.CatalogSku;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class CatalogService {
    private final com.shop.elpsy.mapper.FulfillmentMapper authorization;
    private final CatalogMapper mapper;
    private final CatalogMasterService masters;
    private final InventoryMapper inventory;
    private final com.shop.elpsy.mapper.ImageMapper images;
    public CatalogService(CatalogMapper mapper,InventoryMapper inventory,com.shop.elpsy.mapper.ImageMapper images,com.shop.elpsy.mapper.FulfillmentMapper authorization,CatalogMasterService masters){this.masters=masters;this.mapper=mapper;this.inventory=inventory;this.images=images;this.authorization=authorization;}
    private ResponseStatusException bad(String reason){return new ResponseStatusException(HttpStatus.BAD_REQUEST,reason);}
    private ResponseStatusException conflict(String reason){return new ResponseStatusException(HttpStatus.CONFLICT,reason);}
    private CatalogProduct require(CatalogProduct product){if(product==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"找不到商品");return product;}
    private void version(CatalogProduct p,int version){if(p.getVersion()!=version)throw conflict("商品已被修改，請重新載入");}
    private void editable(CatalogProduct p){if(!"DRAFT".equals(p.getStatus()))throw conflict("只有草稿可編輯或封存，請先下架");}
    private void pagination(int page,int pageSize){if(page<1||page>100000||pageSize<1||pageSize>50)throw bad("分頁範圍不正確");}
    private void metadata(CatalogProduct p,Write w){p.setTitle(w.title().trim());p.setDescription(w.description().trim());}
    private AdminView view(CatalogProduct p){return new AdminView(p.getId(),p.getTitle(),masters.find("BRAND",p.getBrandId()).name(),masters.find("CATEGORY",p.getCategoryId()).name(),p.getDescription(),p.getStatus(),p.getVersion(),mapper.skus(p.getId()).stream().map(s->new Sku(s.getId(),s.getCode(),s.getLabel(),s.getPriceTwd(),s.isEnabled())).toList(),com.shop.elpsy.dto.ImagePayloads.Image.from(images.cover(p.getId()),false),p.getBrandId(),p.getCategoryId(),p.getWorkId(),work(p));}
    private PublicView publicView(CatalogProduct p){return new PublicView(p.getId(),p.getTitle(),masters.find("BRAND",p.getBrandId()).name(),masters.find("CATEGORY",p.getCategoryId()).name(),p.getDescription(),mapper.skus(p.getId()).stream().filter(CatalogSku::isEnabled).map(s->new PublicSku(s.getId(),s.getLabel(),s.getPriceTwd(),s.getQuantity()>0)).toList(),com.shop.elpsy.dto.ImagePayloads.Image.from(images.cover(p.getId()),true),work(p));}
    private String work(CatalogProduct p){return p.getWorkId()==null?null:masters.find("WORK",p.getWorkId()).name();}
    @Transactional(readOnly=true) public Page<AdminView> adminList(int page,int pageSize){pagination(page,pageSize);return new Page<>(mapper.list(false,pageSize,(page-1)*pageSize).stream().map(this::view).toList(),page,pageSize,mapper.count(false));}
    @Transactional(readOnly=true) public AdminView adminDetail(String id){return view(require(mapper.find(id)));}
    @Transactional(readOnly=true) public Page<PublicView> publicList(int page,int pageSize){pagination(page,pageSize);return new Page<>(mapper.list(true,pageSize,(page-1)*pageSize).stream().map(this::publicView).toList(),page,pageSize,mapper.count(true));}
    @Transactional(readOnly=true) public PublicView publicDetail(String id){return publicView(require(mapper.publicFind(id)));}
    private void authorize(com.shop.elpsy.pojo.CustomUserDetails actor,String code){
        if(actor==null||authorization.lockActor(actor.getId())==null||authorization.lockPermission(actor.getId(),code).isEmpty())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"員工或授權已失效");
    }
    @Transactional public AdminView create(Write input,com.shop.elpsy.pojo.CustomUserDetails actor){
        authorize(actor,"catalog.write.create");masters.lock();
        if(input.expectedVersion()!=0)throw bad("新商品版本必須為 0");
        var p=new CatalogProduct();p.setId(UUID.randomUUID().toString());masters.bind(p,input);metadata(p,input);p.setStatus("DRAFT");
        mapper.insert(p);saveSkus(p.getId(),input.skus(),List.of());return view(require(mapper.find(p.getId())));
    }
    @Transactional public AdminView update(String id,Write input,com.shop.elpsy.pojo.CustomUserDetails actor){
        authorize(actor,"catalog.write.update");masters.lock();
        var p=require(mapper.lock(id));version(p,input.expectedVersion());editable(p);masters.bind(p,input);metadata(p,input);
        if(mapper.update(p)!=1)throw conflict("商品已被修改，請重新載入");
        saveSkus(id,input.skus(),mapper.skus(id));return view(require(mapper.find(id)));
    }
    private void saveSkus(String productId,List<Sku> inputs,List<CatalogSku> old){
        var existing=new HashMap<String,CatalogSku>();old.forEach(s->existing.put(s.getId(),s));
        var seenIds=new HashSet<String>();var seenCodes=new HashSet<String>();
        try {for(var input:inputs){
            String code=input.code().toUpperCase(Locale.ROOT);
            if(!seenCodes.add(code))throw bad("SKU 編碼重複");
            var sku=new CatalogSku();sku.setProductId(productId);sku.setCode(code);sku.setLabel(input.label().trim());sku.setPriceTwd(input.priceTwd());sku.setEnabled(input.enabled());
            if(input.id()==null){sku.setId(UUID.randomUUID().toString());mapper.insertSku(sku);inventory.create(sku.getId());}
            else {
                var previous=existing.get(input.id());
                if(previous==null||!seenIds.add(input.id()))throw bad("SKU 不屬於此商品或 ID 重複");
                if(!previous.getCode().equals(code))throw bad("既有 SKU 編碼不可變更；請停用並新增 SKU");
                sku.setId(input.id());mapper.updateSku(sku);
            }
        }}catch(DuplicateKeyException e){throw conflict("SKU 編碼已被使用");}
        if(seenIds.size()!=existing.size())throw bad("不可刪除既有 SKU；請改為停用");
    }
    @Transactional public AdminView publication(String id,Publication input,com.shop.elpsy.pojo.CustomUserDetails actor){
        authorize(actor,"catalog.publish");masters.lock();
        var p=require(mapper.lock(id));version(p,input.expectedVersion());
        if("ARCHIVED".equals(p.getStatus()))throw conflict("封存商品不可上架");
        if(input.published()&&mapper.skus(id).stream().noneMatch(CatalogSku::isEnabled))throw conflict("上架至少需要一個啟用的 SKU");
        if(input.published())masters.publishable(p);
        mapper.state(id,p.getVersion(),input.published()?"PUBLISHED":"DRAFT");return view(require(mapper.find(id)));
    }
    @Transactional public void archive(String id,int expectedVersion,com.shop.elpsy.pojo.CustomUserDetails actor){
        authorize(actor,"catalog.write.archive");masters.lock();
        if(expectedVersion<0)throw bad("版本不正確");
        var p=require(mapper.lock(id));version(p,expectedVersion);editable(p);mapper.state(id,p.getVersion(),"ARCHIVED");
    }
}
