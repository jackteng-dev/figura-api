package com.shop.elpsy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.elpsy.dto.CatalogPayloads.Page;
import com.shop.elpsy.dto.PreorderPayloads.*;
import com.shop.elpsy.mapper.CatalogMapper;
import com.shop.elpsy.mapper.FulfillmentMapper;
import com.shop.elpsy.pojo.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Service
public class PreorderBatchService {
    private static final ZoneOffset TAIPEI=ZoneOffset.ofHours(8);
    private static final String PUBLIC_WHERE="b.opened_at IS NOT NULL AND p.status='PUBLISHED' AND s.enabled=TRUE AND s.product_id=b.product_id";
    private static final String PUBLIC_FROM=" FROM preorder_batch b JOIN catalog_product p ON p.id=b.product_id JOIN catalog_sku s ON s.id=b.sku_id WHERE "+PUBLIC_WHERE;
    private final JdbcTemplate db; private final ObjectMapper json; private final FulfillmentMapper auth;
    private final CatalogMapper catalog; private final CatalogMasterService masters;
    public PreorderBatchService(JdbcTemplate db,ObjectMapper json,FulfillmentMapper auth,CatalogMapper catalog,CatalogMasterService masters){this.db=db;this.json=json;this.auth=auth;this.catalog=catalog;this.masters=masters;}
    private ResponseStatusException fail(int code,String message){return new ResponseStatusException(HttpStatus.valueOf(code),message);}
    private void uuid(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(Exception e){throw fail(400,"預購批次 ID 不正確");}}
    private void page(int page,int size){if(page<1||page>100000||size<1||size>50)throw fail(400,"分頁參數不正確");}
    private OffsetDateTime time(ResultSet r,String field)throws SQLException{LocalDateTime value=r.getObject(field,LocalDateTime.class);return value==null?null:value.atOffset(TAIPEI);}
    private LocalDateTime now(){return db.queryForObject("SELECT CURRENT_TIMESTAMP(6)",(r,n)->r.getObject(1,LocalDateTime.class));}
    private final RowMapper<Batch> batches=(r,n)->new Batch(r.getString("id"),r.getString("product_id"),r.getString("sku_id"),r.getString("name"),r.getString("description"),r.getString("product_title"),r.getString("sku_label"),r.getInt("price_twd"),r.getInt("capacity"),r.getInt("held_quantity"),r.getInt("paid_quantity"),time(r,"deadline_at"),r.getObject("arrival_from",LocalDate.class),r.getObject("arrival_to",LocalDate.class),r.getString("status"),r.getInt("version"),time(r,"opened_at"),time(r,"closed_at"));
    private final RowMapper<Event> events=(r,n)->new Event(r.getString("request_id"),r.getString("batch_id"),r.getInt("actor_id"),r.getString("actor_username"),r.getString("action"),r.getString("fingerprint"),r.getString("before_state"),r.getString("after_state"),r.getString("note"),time(r,"created_at"));
    private Batch find(String id,boolean lock){uuid(id);var found=db.query("SELECT * FROM preorder_batch WHERE id=?"+(lock?" FOR UPDATE":""),batches,id);if(found.isEmpty())throw fail(404,"找不到預購批次");return found.get(0);}
    @Transactional(readOnly=true) public Page<Batch> list(int page,int size){page(page,size);return new Page<>(db.query("SELECT * FROM preorder_batch ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",batches,size,(page-1)*size),page,size,db.queryForObject("SELECT COUNT(*) FROM preorder_batch",Long.class));}
    @Transactional(readOnly=true) public Detail detail(String id){var batch=find(id,false);return new Detail(batch,db.query("SELECT * FROM preorder_batch_event WHERE batch_id=? ORDER BY created_at,request_id",events,id));}
    private PublicBatch publicView(Batch b,LocalDateTime now){String status=b.status();if(status.equals("OPEN")&&!b.deadlineAt().toLocalDateTime().isAfter(now))status="EXPIRED";return new PublicBatch(b.id(),b.productId(),b.name(),b.description(),b.productTitle(),b.skuLabel(),b.priceTwd(),b.capacity()-b.heldQuantity()-b.paidQuantity(),b.deadlineAt(),b.arrivalFrom(),b.arrivalTo(),status,status.equals("OPEN")&&b.capacity()>b.heldQuantity()+b.paidQuantity());}
    @Transactional(readOnly=true) public Page<PublicBatch> publicList(int page,int size){page(page,size);var at=now();return new Page<>(db.query("SELECT b.*"+PUBLIC_FROM+" ORDER BY b.created_at DESC,b.id DESC LIMIT ? OFFSET ?",batches,size,(page-1)*size).stream().map(b->publicView(b,at)).toList(),page,size,db.queryForObject("SELECT COUNT(*)"+PUBLIC_FROM,Long.class));}
    @Transactional(readOnly=true) public PublicBatch publicDetail(String id){uuid(id);var rows=db.query("SELECT b.*"+PUBLIC_FROM+" AND b.id=?",batches,id);if(rows.isEmpty())throw fail(404,"找不到預購批次，可能尚未開放或商品已下架");return publicView(rows.get(0),now());}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String fingerprint(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    // Same ordering as catalog writes. The master singleton also serializes all batch commands,
    // including requestId checks across different batches/actors, before any resource lock.
    private String authorize(CustomUserDetails actor,String permission){String username=actor==null?null:auth.lockActor(actor.getId());if(username==null||auth.lockPermission(actor.getId(),permission).isEmpty())throw fail(403,"員工或預購授權已失效");masters.lock();return username;}
    private Applied replay(String request,String id,String action,String hash,CustomUserDetails actor){var old=db.query("SELECT * FROM preorder_batch_event WHERE request_id=?",events,request);if(old.isEmpty())return null;var e=old.get(0);if(e.actorId()!=actor.getId()||!e.action().equals(action)||(id!=null&&!e.batchId().equals(id))||!e.fingerprint().equals(hash))throw fail(409,"requestId 已用於不同預購操作");return new Applied(e,find(e.batchId(),false),true);}
    private Applied append(String id,String action,String hash,String request,String note,CustomUserDetails actor,String username,Batch before){var after=find(id,false);db.update("INSERT INTO preorder_batch_event(request_id,batch_id,actor_id,actor_username,action,fingerprint,before_state,after_state,note) VALUES(?,?,?,?,?,?,?,?,?)",request,id,actor.getId(),username,action,hash,before==null?null:encode(before),encode(after),note);return new Applied(db.queryForObject("SELECT * FROM preorder_batch_event WHERE request_id=?",events,request),after,false);}
    private void version(Batch b,int expected){if(b.version()!=expected)throw fail(409,"預購批次版本已變更，請先重讀");}
    private LocalDateTime deadline(Write in){if(in.deadlineAt().getNano()!=0)throw fail(400,"截止時間需精確到秒");var deadline=in.deadlineAt().withOffsetSameInstant(TAIPEI).toLocalDateTime();if(deadline.getYear()<2000||deadline.getYear()>2100||in.arrivalFrom().getYear()>2100||in.arrivalTo().getYear()>2100||in.arrivalFrom().isAfter(in.arrivalTo())||deadline.toLocalDate().isAfter(in.arrivalFrom()))throw fail(400,"截止時間與到貨區間不正確");if(!deadline.isAfter(now()))throw fail(409,"截止時間需在未來");return deadline;}
    @Transactional(isolation=Isolation.READ_COMMITTED) public Applied save(String id,Write in,CustomUserDetails actor){
        if(id!=null)uuid(id);String action=id==null?"CREATE":"UPDATE";String username=authorize(actor,id==null?"preorders.create":"preorders.update");
        String name=in.name().trim(),description=in.description().trim(),note=in.note().trim();if(name.isBlank()||note.isBlank())throw fail(400,"批次名稱與異動原因不可空白");
        var normalized=in.deadlineAt().withOffsetSameInstant(TAIPEI);
        String hash=fingerprint(List.of(in.productId(),in.skuId(),name,description,in.priceTwd(),in.capacity(),normalized,in.arrivalFrom(),in.arrivalTo(),in.expectedVersion(),note));var retry=replay(in.requestId(),id,action,hash,actor);if(retry!=null)return retry;
        var product=catalog.lock(in.productId());if(product==null)throw fail(404,"找不到商品");if("ARCHIVED".equals(product.getStatus()))throw fail(409,"封存商品不能建立或編輯預購批次");
        var sku=catalog.skus(product.getId()).stream().filter(s->s.getId().equals(in.skuId())).findFirst().orElseThrow(()->fail(409,"SKU 不屬於此商品"));if(!sku.isEnabled())throw fail(409,"預購需要啟用的 SKU");
        Batch before=null;if(id==null){if(in.expectedVersion()!=0)throw fail(409,"新批次版本需為 0");id=UUID.randomUUID().toString();}else{before=find(id,true);version(before,in.expectedVersion());if(!before.status().equals("DRAFT"))throw fail(409,"只有草稿批次可編輯");if(!before.productId().equals(in.productId())||!before.skuId().equals(in.skuId()))throw fail(409,"批次商品與 SKU 不可更換，請建立新批次");}
        var cutoff=deadline(in);
        if(before==null)db.update("INSERT INTO preorder_batch(id,product_id,sku_id,name,description,product_title,sku_label,price_twd,capacity,deadline_at,arrival_from,arrival_to) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",id,in.productId(),in.skuId(),name,description,product.getTitle(),sku.getLabel(),in.priceTwd(),in.capacity(),cutoff,in.arrivalFrom(),in.arrivalTo());
        else db.update("UPDATE preorder_batch SET name=?,description=?,product_title=?,sku_label=?,price_twd=?,capacity=?,deadline_at=?,arrival_from=?,arrival_to=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",name,description,product.getTitle(),sku.getLabel(),in.priceTwd(),in.capacity(),cutoff,in.arrivalFrom(),in.arrivalTo(),id);
        return append(id,action,hash,in.requestId(),note,actor,username,before);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public Applied command(String id,String action,Command in,CustomUserDetails actor){
        uuid(id);if(!Set.of("OPEN","CLOSE").contains(action))throw fail(400,"不支援的預購操作");String username=authorize(actor,action.equals("OPEN")?"preorders.open":"preorders.close");String note=in.note().trim();if(note.isBlank())throw fail(400,"異動原因不可空白");String hash=fingerprint(List.of(in.expectedVersion(),note));var retry=replay(in.requestId(),id,action,hash,actor);if(retry!=null)return retry;
        var reference=find(id,false);var product=catalog.lock(reference.productId());var batch=find(id,true);version(batch,in.expectedVersion());
        if(action.equals("OPEN")){
            if(!batch.status().equals("DRAFT"))throw fail(409,"只有草稿可以開放，截單後不能重新開放");
            if(!batch.deadlineAt().toLocalDateTime().isAfter(now()))throw fail(409,"批次已到截止時間");
            if(product==null||!"PUBLISHED".equals(product.getStatus()))throw fail(409,"預購開放前需上架商品");masters.publishable(product);
            var sku=catalog.skus(product.getId()).stream().filter(s->s.getId().equals(batch.skuId())&&s.isEnabled()).findFirst().orElseThrow(()->fail(409,"預購 SKU 已停用"));
            db.update("UPDATE preorder_batch SET status='OPEN',product_title=?,sku_label=?,opened_at=CURRENT_TIMESTAMP(6),version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",product.getTitle(),sku.getLabel(),id);
        }else{
            if(batch.status().equals("CLOSED"))throw fail(409,"批次已截單");
            db.update("UPDATE preorder_batch SET status='CLOSED',closed_at=CURRENT_TIMESTAMP(6),version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);
        }
        return append(id,action,hash,in.requestId(),note,actor,username,batch);
    }
}
