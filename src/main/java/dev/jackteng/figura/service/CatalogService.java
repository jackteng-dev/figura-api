package dev.jackteng.figura.service;
import dev.jackteng.figura.dto.CatalogPayloads.*;
import static dev.jackteng.figura.dto.CatalogPayloads.*;
import dev.jackteng.figura.mapper.CatalogMapper;
import dev.jackteng.figura.mapper.InventoryMapper;
import dev.jackteng.figura.pojo.CatalogProduct;
import dev.jackteng.figura.pojo.CatalogSku;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class CatalogService {
    private final dev.jackteng.figura.mapper.FulfillmentMapper authorization;
    private final CatalogMapper mapper;
    private final CatalogMasterService masters;
    private final InventoryMapper inventory;
    private final dev.jackteng.figura.mapper.ImageMapper images;
    public CatalogService(CatalogMapper mapper,InventoryMapper inventory,dev.jackteng.figura.mapper.ImageMapper images,dev.jackteng.figura.mapper.FulfillmentMapper authorization,CatalogMasterService masters){this.masters=masters;this.mapper=mapper;this.inventory=inventory;this.images=images;this.authorization=authorization;}
    private ResponseStatusException bad(String reason){return new ResponseStatusException(HttpStatus.BAD_REQUEST,reason);}
    private ResponseStatusException conflict(String reason){return new ResponseStatusException(HttpStatus.CONFLICT,reason);}
    private CatalogProduct require(CatalogProduct product){if(product==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"找不到商品");return product;}
    private void version(CatalogProduct p,int version){if(p.getVersion()!=version)throw conflict("商品已被修改，請重新載入");}
    private void editable(CatalogProduct p){if(!"DRAFT".equals(p.getStatus()))throw conflict("只有草稿可編輯或封存，請先下架");}
    private void pagination(int page,int pageSize){if(page<1||page>100000||pageSize<1||pageSize>50)throw bad("分頁範圍不正確");}
    private void metadata(CatalogProduct p,Write w){p.setTitle(w.title().trim());p.setDescription(w.description().trim());if(w.specs()!=null)specs(p,w.specs());if(p.getContentOrigin()==null)p.setContentOrigin("REAL");if(p.getIsLimited()==null)p.setIsLimited(false);}
    private static String blank(String v){return v==null||v.isBlank()?null:v.trim();}
    private static String codes(List<String> values,List<String> allowed,String label){
        if(values==null||values.isEmpty())return null;
        var out=new LinkedHashSet<String>();for(String v:values){String c=v.trim().toUpperCase(Locale.ROOT);if(!allowed.contains(c))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,label+"代碼不正確："+v);out.add(c);}
        return String.join(",",out);
    }
    private static List<String> split(String v){return v==null||v.isBlank()?List.of():Arrays.asList(v.split(","));}
    private static List<String> lines(String v){return v==null||v.isBlank()?List.of():Arrays.asList(v.split("\n"));}
    /** 整組取代規格；未提供的欄位存 NULL（不杜撰）。AI 概念商品的設定材質只存 concept_materials。 */
    private void specs(CatalogProduct p,Specs s){
        String origin=s.contentOrigin()==null?(p.getContentOrigin()==null?"REAL":p.getContentOrigin()):s.contentOrigin();
        if(blank(s.scale())!=null&&!SCALES.contains(s.scale().trim().toUpperCase(Locale.ROOT)))throw bad("比例代碼不正確");
        if(blank(s.productType())!=null&&!PRODUCT_TYPES.contains(s.productType().trim()))throw bad("商品類型代碼不正確");
        if(blank(s.edition())!=null&&!EDITIONS.contains(s.edition().trim()))throw bad("商品版本代碼不正確");
        if(s.releaseDate()!=null&&s.releaseMonth()!=null)throw bad("預計發售日與發售月份請擇一");
        if(s.releaseDate()!=null&&(s.releaseDate().getYear()<2000||s.releaseDate().getYear()>2100))throw bad("預計發售日不正確");
        if("REAL".equals(origin)&&s.conceptMaterials()!=null&&!s.conceptMaterials().isEmpty())throw bad("只有 AI 概念商品可填設定材質");
        String accessories=s.accessories()==null?null:String.join("\n",s.accessories().stream().map(String::trim).filter(x->!x.isEmpty()).toList());
        if(accessories!=null&&accessories.length()>1000)throw bad("配件內容過長");
        p.setCharacterName(blank(s.characterName()));p.setManufacturer(blank(s.manufacturer()));
        p.setScale(blank(s.scale())==null?null:s.scale().trim().toUpperCase(Locale.ROOT));p.setHeightMm(s.heightMm());p.setDimensions(blank(s.dimensions()));
        p.setMaterials(codes(s.materials(),MATERIALS,"材質"));p.setProductType(blank(s.productType()));p.setEdition(blank(s.edition()));
        p.setIsLimited(Boolean.TRUE.equals(s.limited()));p.setAccessories(blank(accessories));p.setReleaseDate(s.releaseDate());p.setReleaseMonth(blank(s.releaseMonth()));
        p.setSpecNote(blank(s.specNote()));p.setContentOrigin(origin);p.setConceptMaterials(codes(s.conceptMaterials(),MATERIALS,"設定材質"));
    }
    private Specs specsOf(CatalogProduct p,boolean publicView){
        boolean ai="AI_CONCEPT".equals(p.getContentOrigin());
        return new Specs(p.getCharacterName(),p.getManufacturer(),p.getScale(),p.getHeightMm(),p.getDimensions(),publicView&&ai?List.of():split(p.getMaterials()),
                p.getProductType(),p.getEdition(),Boolean.TRUE.equals(p.getIsLimited()),lines(p.getAccessories()),p.getReleaseDate(),p.getReleaseMonth(),p.getSpecNote(),
                p.getContentOrigin(),split(p.getConceptMaterials()));
    }
    /** 伺服器維護的搜尋文字：標題、角色、作品、品牌、製造商。 */
    private void searchText(CatalogProduct p){
        String brand=masters.find("BRAND",p.getBrandId()).name(),work=work(p);
        String text=String.join(" ",java.util.stream.Stream.of(p.getTitle(),p.getCharacterName(),work,brand,p.getManufacturer()).filter(Objects::nonNull).toList());
        p.setSearchText(text.length()>1000?text.substring(0,1000):text);
    }
    private AdminView view(CatalogProduct p){return new AdminView(p.getId(),p.getTitle(),masters.find("BRAND",p.getBrandId()).name(),masters.find("CATEGORY",p.getCategoryId()).name(),p.getDescription(),p.getStatus(),p.getVersion(),mapper.skus(p.getId()).stream().map(s->new Sku(s.getId(),s.getCode(),s.getLabel(),s.getPriceTwd(),s.isEnabled())).toList(),dev.jackteng.figura.dto.ImagePayloads.Image.from(images.cover(p.getId()),false),p.getBrandId(),p.getCategoryId(),p.getWorkId(),work(p),specsOf(p,false),p.getContentOrigin());}
    private static java.time.OffsetDateTime taipei(Object v){if(v==null)return null;var t=v instanceof java.sql.Timestamp ts?ts.toLocalDateTime():(java.time.LocalDateTime)v;return t.atOffset(java.time.ZoneOffset.ofHours(8));}
    private static java.time.LocalDate day(Object v){return v==null?null:v instanceof java.sql.Date d?d.toLocalDate():(java.time.LocalDate)v;}
    private PublicView publicView(CatalogProduct p){
        var skus=mapper.skus(p.getId()).stream().filter(CatalogSku::isEnabled).map(s->new PublicSku(s.getId(),s.getLabel(),s.getPriceTwd(),s.getQuantity()>0)).toList();
        var b=mapper.openBatch(p.getId());
        PreorderSummary preorder=b==null?null:new PreorderSummary((String)b.get("batchId"),taipei(b.get("openedAt")),taipei(b.get("deadlineAt")),day(b.get("arrivalFrom")),day(b.get("arrivalTo")),((Number)b.get("priceTwd")).intValue(),((Number)b.get("remaining")).intValue()>0);
        String availability=skus.stream().anyMatch(PublicSku::available)?"IN_STOCK":preorder!=null&&preorder.orderingEnabled()?"PREORDER":"SOLD_OUT";
        Integer min=skus.stream().map(PublicSku::priceTwd).min(Integer::compare).orElse(null);
        return new PublicView(p.getId(),p.getTitle(),masters.find("BRAND",p.getBrandId()).name(),masters.find("CATEGORY",p.getCategoryId()).name(),p.getDescription(),skus,dev.jackteng.figura.dto.ImagePayloads.Image.from(images.cover(p.getId()),true),work(p),
                specsOf(p,true),p.getContentOrigin(),availability,min,preorder,p.getUpdatedAt()==null?null:p.getUpdatedAt().atOffset(java.time.ZoneOffset.ofHours(8)));
    }
    private String work(CatalogProduct p){return p.getWorkId()==null?null:masters.find("WORK",p.getWorkId()).name();}
    @Transactional(readOnly=true) public Page<AdminView> adminList(int page,int pageSize){pagination(page,pageSize);return new Page<>(mapper.list(false,pageSize,(page-1)*pageSize).stream().map(this::view).toList(),page,pageSize,mapper.count(false));}
    @Transactional(readOnly=true) public AdminView adminDetail(String id){return view(require(mapper.find(id)));}
    @Transactional(readOnly=true) public Page<PublicView> publicList(int page,int pageSize){pagination(page,pageSize);return new Page<>(mapper.list(true,pageSize,(page-1)*pageSize).stream().map(this::publicView).toList(),page,pageSize,mapper.count(true));}
    @Transactional(readOnly=true) public PublicView publicDetail(String id){return publicView(require(mapper.publicFind(id)));}
    private void authorize(dev.jackteng.figura.pojo.CustomUserDetails actor,String code){
        if(actor==null||authorization.lockActor(actor.getId())==null||authorization.lockPermission(actor.getId(),code).isEmpty())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"員工或授權已失效");
    }
    @Transactional public AdminView create(Write input,dev.jackteng.figura.pojo.CustomUserDetails actor){
        authorize(actor,"catalog.write.create");masters.lock();
        if(input.expectedVersion()!=0)throw bad("新商品版本必須為 0");
        var p=new CatalogProduct();p.setId(UUID.randomUUID().toString());masters.bind(p,input);metadata(p,input);p.setStatus("DRAFT");searchText(p);
        mapper.insert(p);saveSkus(p.getId(),input.skus(),List.of());return view(require(mapper.find(p.getId())));
    }
    @Transactional public AdminView update(String id,Write input,dev.jackteng.figura.pojo.CustomUserDetails actor){
        authorize(actor,"catalog.write.update");masters.lock();
        var p=require(mapper.lock(id));version(p,input.expectedVersion());editable(p);masters.bind(p,input);metadata(p,input);searchText(p);
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
    @Transactional public AdminView publication(String id,Publication input,dev.jackteng.figura.pojo.CustomUserDetails actor){
        authorize(actor,"catalog.publish");masters.lock();
        var p=require(mapper.lock(id));version(p,input.expectedVersion());
        if("ARCHIVED".equals(p.getStatus()))throw conflict("封存商品不可上架");
        if(input.published()&&mapper.skus(id).stream().noneMatch(CatalogSku::isEnabled))throw conflict("上架至少需要一個啟用的 SKU");
        if(input.published())masters.publishable(p);
        mapper.state(id,p.getVersion(),input.published()?"PUBLISHED":"DRAFT");return view(require(mapper.find(id)));
    }
    @Transactional public void archive(String id,int expectedVersion,dev.jackteng.figura.pojo.CustomUserDetails actor){
        authorize(actor,"catalog.write.archive");masters.lock();
        if(expectedVersion<0)throw bad("版本不正確");
        var p=require(mapper.lock(id));version(p,expectedVersion);editable(p);mapper.state(id,p.getVersion(),"ARCHIVED");
    }
}
