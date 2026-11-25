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
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate sql;
    public CatalogService(CatalogMapper mapper,InventoryMapper inventory,dev.jackteng.figura.mapper.ImageMapper images,dev.jackteng.figura.mapper.FulfillmentMapper authorization,CatalogMasterService masters,org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate sql){this.masters=masters;this.mapper=mapper;this.inventory=inventory;this.images=images;this.authorization=authorization;this.sql=sql;}
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
    @Transactional(readOnly=true) public Page<PublicView> publicList(int page,int pageSize){return search(Search.none(),page,pageSize);}

    // ---- Commerce V2 Phase 2：後端搜尋與篩選。可售狀態在查詢中推導（與 publicView 相同規則：現貨 > 預購 > 售完）。
    private static final String PUBLIC_FROM=" FROM catalog_product p WHERE p.status='PUBLISHED' AND EXISTS(SELECT 1 FROM catalog_sku s WHERE s.product_id=p.id AND s.enabled=TRUE)";
    static final String IN_STOCK="EXISTS(SELECT 1 FROM catalog_sku s JOIN sku_inventory i ON i.sku_id=s.id WHERE s.product_id=p.id AND s.enabled=TRUE AND i.quantity-i.reserved>0)";
    /** 可下單的預購批次（別名 b）：開放、未截止、有剩餘配額，且批次款式仍屬該商品並啟用。搜尋、商品摘要與提醒掃描共用。 */
    static final String ORDERABLE_BATCH="b.status='OPEN' AND b.deadline_at>CURRENT_TIMESTAMP(6) AND b.capacity-b.held_quantity-b.paid_quantity>0 AND EXISTS(SELECT 1 FROM catalog_sku k WHERE k.id=b.sku_id AND k.product_id=b.product_id AND k.enabled=TRUE)";
    static final String OPEN_BATCH="EXISTS(SELECT 1 FROM preorder_batch b WHERE b.product_id=p.id AND "+ORDERABLE_BATCH+")";
    private static final String AVAIL="(CASE WHEN "+IN_STOCK+" THEN 'IN_STOCK' WHEN "+OPEN_BATCH+" THEN 'PREORDER' ELSE 'SOLD_OUT' END)";
    private static final String MIN_PRICE="(SELECT MIN(s.price_twd) FROM catalog_sku s WHERE s.product_id=p.id AND s.enabled=TRUE)";
    private static final String RELEASE="COALESCE(p.release_date,STR_TO_DATE(CONCAT(p.release_month,'-01'),'%Y-%m-%d'))";
    private static final java.util.regex.Pattern UUID_RE=java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static List<String> clean(List<String> v){return v==null?List.of():v.stream().filter(Objects::nonNull).flatMap(x->Arrays.stream(x.split(","))).map(String::trim).filter(x->!x.isEmpty()).distinct().toList();}
    private List<String> allowed(List<String> v,List<String> allowed,String label,boolean upper){var out=clean(v).stream().map(x->upper?x.toUpperCase(Locale.ROOT):x).toList();for(String x:out)if(!allowed.contains(x))throw bad(label+"不正確："+x);if(out.size()>20)throw bad(label+"過多");return out;}
    private List<String> ids(List<String> v,String label){var out=clean(v);for(String x:out)if(!UUID_RE.matcher(x).matches())throw bad(label+"不正確");if(out.size()>20)throw bad(label+"過多");return out;}
    /** 依條件組 WHERE；回傳 SQL 片段並填入參數。 */
    private String where(Search s,org.springframework.jdbc.core.namedparam.MapSqlParameterSource args,boolean includeAvailability){
        var w=new StringBuilder(PUBLIC_FROM);
        String q=s.q()==null?"":s.q().trim();
        if(q.length()>80)throw bad("搜尋文字過長");
        if(!q.isEmpty()){
            // ngram 斷詞最短 2 字；1 字或含特殊符號時改用 LIKE（商品數量有限，仍可接受）
            String term=q.replaceAll("[+\\-<>()~*\"@]"," ").trim();
            if(term.codePointCount(0,term.length())>=2){w.append(" AND MATCH(p.search_text) AGAINST(:q IN BOOLEAN MODE)");args.addValue("q",Arrays.stream(term.split("\\s+")).filter(x->!x.isEmpty()).map(x->"+\""+x+"\"").reduce((a,b)->a+" "+b).orElse(term));}
            else {w.append(" AND p.search_text LIKE :like");args.addValue("like","%"+q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");}
        }
        var brands=ids(s.brandIds(),"品牌");if(!brands.isEmpty()){w.append(" AND p.brand_id IN (:brands)");args.addValue("brands",brands);}
        var works=ids(s.workIds(),"作品");if(!works.isEmpty()){w.append(" AND p.work_id IN (:works)");args.addValue("works",works);}
        var cats=ids(s.categoryIds(),"分類");if(!cats.isEmpty()){w.append(" AND p.category_id IN (:cats)");args.addValue("cats",cats);}
        var makers=clean(s.manufacturers());if(makers.size()>20)throw bad("製造商過多");if(!makers.isEmpty()){w.append(" AND p.manufacturer IN (:makers)");args.addValue("makers",makers);}
        var scales=allowed(s.scales(),SCALES,"比例",true);if(!scales.isEmpty()){w.append(" AND p.scale IN (:scales)");args.addValue("scales",scales);}
        var types=allowed(s.productTypes(),PRODUCT_TYPES,"商品類型",true);if(!types.isEmpty()){w.append(" AND p.product_type IN (:types)");args.addValue("types",types);}
        var eds=allowed(s.editions(),EDITIONS,"商品版本",true);if(!eds.isEmpty()){w.append(" AND p.edition IN (:eds)");args.addValue("eds",eds);}
        var mats=allowed(s.materials(),MATERIALS,"材質",true);
        // AI 概念商品沒有真實材質，材質篩選只比對真實商品
        if(!mats.isEmpty()){w.append(" AND p.content_origin='REAL' AND (");for(int i=0;i<mats.size();i++){w.append(i>0?" OR ":"").append("FIND_IN_SET(:mat").append(i).append(",p.materials)>0");args.addValue("mat"+i,mats.get(i));}w.append(")");}
        if(Boolean.TRUE.equals(s.limited()))w.append(" AND p.is_limited=TRUE");
        if(s.priceMin()!=null){if(s.priceMin()<0)throw bad("價格範圍不正確");w.append(" AND "+MIN_PRICE+">=:pmin");args.addValue("pmin",s.priceMin());}
        if(s.priceMax()!=null){if(s.priceMax()<0||(s.priceMin()!=null&&s.priceMax()<s.priceMin()))throw bad("價格範圍不正確");w.append(" AND "+MIN_PRICE+"<=:pmax");args.addValue("pmax",s.priceMax());}
        if(includeAvailability){var av=allowed(s.availability(),AVAILABILITY,"可售狀態",true);if(!av.isEmpty()){w.append(" AND "+AVAIL+" IN (:avail)");args.addValue("avail",av);}}
        return w.toString();
    }
    private String order(String sort,boolean hasQuery){
        String s=sort==null||sort.isBlank()?(hasQuery?"RELEVANCE":"NEWEST"):sort.trim().toUpperCase(Locale.ROOT);
        if(!SORTS.contains(s))throw bad("排序方式不正確");
        return switch(s){
            case "PRICE_ASC"->" ORDER BY "+MIN_PRICE+" ASC,p.created_at DESC,p.id DESC";
            case "PRICE_DESC"->" ORDER BY "+MIN_PRICE+" DESC,p.created_at DESC,p.id DESC";
            case "RELEASE_ASC"->" ORDER BY "+RELEASE+" IS NULL,"+RELEASE+" ASC,p.created_at DESC,p.id DESC";
            case "RELEASE_DESC"->" ORDER BY "+RELEASE+" IS NULL,"+RELEASE+" DESC,p.created_at DESC,p.id DESC";
            case "RELEVANCE"->hasQuery?" ORDER BY MATCH(p.search_text) AGAINST(:qn IN NATURAL LANGUAGE MODE) DESC,p.created_at DESC,p.id DESC":" ORDER BY p.created_at DESC,p.id DESC";
            case "STOCK_FIRST"->" ORDER BY "+IN_STOCK+" DESC,p.created_at DESC,p.id DESC";
            default->" ORDER BY p.created_at DESC,p.id DESC";
        };
    }
    @Transactional(readOnly=true) public Page<PublicView> search(Search s,int page,int pageSize){
        if(page<1||page>1000||pageSize<1||pageSize>50)throw bad("分頁範圍不正確");
        var args=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
        String where=where(s,args,true);boolean hasQuery=s.q()!=null&&!s.q().isBlank();
        if(hasQuery)args.addValue("qn",s.q().trim());
        long total=sql.queryForObject("SELECT COUNT(*)"+where,args,Long.class);
        args.addValue("limit",pageSize).addValue("offset",(page-1)*pageSize);
        var ids=sql.queryForList("SELECT p.id"+where+order(s.sort(),hasQuery)+" LIMIT :limit OFFSET :offset",args,String.class);
        return new Page<>(ids.stream().map(id->publicView(require(mapper.find(id)))).toList(),page,pageSize,total);
    }
    private List<FacetValue> group(String expr,String labelExpr,String where,org.springframework.jdbc.core.namedparam.MapSqlParameterSource args){
        return sql.query("SELECT "+expr+" v,"+labelExpr+" l,COUNT(*) c"+where+" AND "+expr+" IS NOT NULL GROUP BY v,l ORDER BY c DESC,l",args,(r,n)->new FacetValue(r.getString("v"),r.getString("l"),r.getLong("c")));
    }
    /** 篩選選項與筆數：套用目前全部條件；只有「可售狀態」本身的筆數不套用可售狀態（多選時各選項仍顯示可選數）。只算上架商品。 */
    @Transactional(readOnly=true) public Facets facets(Search s){
        var args=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();String anyAvail=where(s,args,false),where=where(s,args,true);
        var materials=new TreeMap<String,Long>();
        for(var row:sql.queryForList("SELECT p.materials m"+where+" AND p.content_origin='REAL' AND p.materials IS NOT NULL",args))for(String m:String.valueOf(row.get("m")).split(","))materials.merge(m,1L,Long::sum);
        var price=sql.queryForMap("SELECT MIN("+MIN_PRICE+") lo,MAX("+MIN_PRICE+") hi,COUNT(*) c"+where,args);
        return new Facets(group("p.brand_id","(SELECT name FROM catalog_brand x WHERE x.id=p.brand_id)",where,args),
                group("p.work_id","(SELECT name FROM catalog_work x WHERE x.id=p.work_id)",where,args),
                group("p.category_id","(SELECT name FROM catalog_category x WHERE x.id=p.category_id)",where,args),
                group("p.manufacturer","p.manufacturer",where,args),group(AVAIL,AVAIL,anyAvail,args),
                group("p.scale","p.scale",where,args),materials.entrySet().stream().map(e->new FacetValue(e.getKey(),e.getKey(),e.getValue())).toList(),
                group("p.product_type","p.product_type",where,args),group("p.edition","p.edition",where,args),
                price.get("lo")==null?null:((Number)price.get("lo")).intValue(),price.get("hi")==null?null:((Number)price.get("hi")).intValue(),((Number)price.get("c")).longValue());
    }
    @Transactional(readOnly=true) public PublicView publicDetail(String id){return publicView(require(mapper.publicFind(id)));}
    /** 收藏／提醒用：只回目前公開的商品（下架或無可用款式者略過）。 */
    @Transactional(readOnly=true) public Map<String,PublicView> publicViews(List<String> ids){
        var out=new LinkedHashMap<String,PublicView>();for(String id:ids){var p=mapper.publicFind(id);if(p!=null)out.put(id,publicView(p));}return out;}
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
