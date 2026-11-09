package com.shop.elpsy.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.elpsy.dto.MasterPayloads.*;
import com.shop.elpsy.dto.CatalogPayloads.Page;
import com.shop.elpsy.mapper.FulfillmentMapper;
import com.shop.elpsy.pojo.CatalogProduct;
import com.shop.elpsy.pojo.CustomUserDetails;
import org.springframework.jdbc.core.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
@Service
public class CatalogMasterService {
 private final JdbcTemplate db;private final ObjectMapper json;private final FulfillmentMapper auth;
 private static final Map<String,String> TABLES=Map.of("BRAND","catalog_brand","WORK","catalog_work","CATEGORY","catalog_category");
 private static final Map<String,String> COLUMNS=Map.of("BRAND","brand_id","WORK","work_id","CATEGORY","category_id");
 public CatalogMasterService(JdbcTemplate db,ObjectMapper json,FulfillmentMapper auth){this.db=db;this.json=json;this.auth=auth;}
 private String table(String kind){String table=TABLES.get(kind);if(table==null)throw fail(400,"主檔類型不正確");return table;}
 private void uuid(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(Exception e){throw fail(400,"主檔ID不正確");}}
 private RowMapper<Master> row(String kind){return (r,n)->new Master(r.getString("id"),kind,r.getString("name"),r.getString("description"),r.getBoolean("enabled"),r.getInt("version"));}
 public Master find(String kind,String id){uuid(id);var rows=db.query("SELECT * FROM "+table(kind)+" WHERE id=?",row(kind),id);if(rows.isEmpty())throw fail(404,"找不到對應類型的主檔");return rows.get(0);}
 public Master name(String kind,String name){var rows=db.query("SELECT * FROM "+table(kind)+" WHERE name=?",row(kind),name.trim());if(rows.isEmpty())throw fail(409,"名稱尚未建立為主檔，請先由有權限員工新增");return rows.get(0);}
 private void page(int page,int size){if(page<1||page>100000||size<1||size>50)throw fail(400,"分頁參數不正確");}
 public Page<Master> list(String kind,int page,int size){page(page,size);String t=table(kind);return new Page<>(db.query("SELECT * FROM "+t+" ORDER BY name,id LIMIT ? OFFSET ?",row(kind),size,(page-1)*size),page,size,db.queryForObject("SELECT COUNT(*) FROM "+t,Long.class));}
 private final RowMapper<Event> events=(r,n)->new Event(r.getString("request_id"),r.getInt("actor_id"),r.getString("actor_username"),r.getString("kind"),r.getString("action"),r.getString("target_id"),r.getString("fingerprint"),r.getString("before_state"),r.getString("after_state"),r.getString("note"),r.getObject("created_at",java.time.LocalDateTime.class));
 public Page<Event> audit(int page,int size){page(page,size);return new Page<>(db.query("SELECT * FROM catalog_master_event ORDER BY created_at DESC,request_id DESC LIMIT ? OFFSET ?",events,size,(page-1)*size),page,size,db.queryForObject("SELECT COUNT(*) FROM catalog_master_event",Long.class));}
 /** Only invoke inside a transaction, after live actor/grant; before any product lock. */
 public void lock(){db.queryForObject("SELECT id FROM catalog_master_guard WHERE id=1 FOR UPDATE",Integer.class);}
 private void active(Master master,String retained){if(!master.enabled()&&!master.id().equals(retained))throw fail(409,"停用主檔不可新增指派");}
 public void bind(CatalogProduct p,com.shop.elpsy.dto.CatalogPayloads.Write in){
   boolean ids=in.brandId()!=null||in.categoryId()!=null;
   var b=ids?find("BRAND",in.brandId()):name("BRAND",in.brand());var c=ids?find("CATEGORY",in.categoryId()):name("CATEGORY",in.category());
   if(ids&&((in.brand()!=null&&!in.brand().trim().equals(b.name()))||(in.category()!=null&&!in.category().trim().equals(c.name()))))throw fail(409,"主檔名稱已變更，請重新核對ID");
   active(b,p.getBrandId());active(c,p.getCategoryId());String work=ids?in.workId():(in.workId()!=null?in.workId():p.getWorkId());
   if(work!=null)active(find("WORK",work),p.getWorkId());
   p.setBrandId(b.id());p.setCategoryId(c.id());p.setWorkId(work);p.setBrand(b.name());p.setCategory(c.name());
 }
 public void publishable(CatalogProduct p){if(!find("BRAND",p.getBrandId()).enabled()||!find("CATEGORY",p.getCategoryId()).enabled()||(p.getWorkId()!=null&&!find("WORK",p.getWorkId()).enabled()))throw fail(409,"商品引用停用主檔，請先更換或啟用後再上架");}
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Applied save(String kind,String id,Write in,CustomUserDetails actor){
   String t=table(kind);if(id!=null)uuid(id);String action=id==null?"CREATE":"UPDATE";
   String username=actor==null?null:auth.lockActor(actor.getId());if(username==null||auth.lockPermission(actor.getId(),id==null?"masters.create":"masters.update").isEmpty())throw fail(403,"員工或主檔授權已失效");lock();
   String name=in.name().trim(),description=in.description().trim(),note=in.note().trim();if(name.isBlank()||note.isBlank())throw fail(400,"名稱與原因不可空白");
   String fingerprint;try{fingerprint=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(encode(List.of(name,description,in.enabled(),in.expectedVersion(),note)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
   var prior=db.query("SELECT * FROM catalog_master_event WHERE request_id=?",events,in.requestId());
   if(!prior.isEmpty()){var e=prior.get(0);if(e.actorId()!=actor.getId()||!e.kind().equals(kind)||!e.action().equals(action)||(id!=null&&!e.targetId().equals(id))||!e.fingerprint().equals(fingerprint))throw fail(409,"requestId 已用於不同主檔操作");return new Applied(e,find(kind,e.targetId()),true);}
   Master before=null;
   if(id==null){if(in.expectedVersion()!=0)throw fail(409,"新主檔版本需為0");id=UUID.randomUUID().toString();}
   else{before=find(kind,id);if(before.version()!=in.expectedVersion())throw fail(409,"主檔版本已變更，請先重讀");if(!in.enabled()&&db.queryForObject("SELECT COUNT(*) FROM catalog_product WHERE status='PUBLISHED' AND "+COLUMNS.get(kind)+"=?",Long.class,id)>0)throw fail(409,"上架商品仍引用此主檔，請先下架或更換");}
   try{if(before==null)db.update("INSERT INTO "+t+"(id,name,description,enabled) VALUES(?,?,?,?)",id,name,description,in.enabled());else db.update("UPDATE "+t+" SET name=?,description=?,enabled=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",name,description,in.enabled(),id);}catch(DuplicateKeyException e){throw fail(409,"同類型主檔名稱已存在");}
   var after=find(kind,id);db.update("INSERT INTO catalog_master_event(request_id,actor_id,actor_username,kind,action,target_id,fingerprint,before_state,after_state,note) VALUES(?,?,?,?,?,?,?,?,?,?)",in.requestId(),actor.getId(),username,kind,action,id,fingerprint,before==null?null:encode(before),encode(after),note);
   return new Applied(db.queryForObject("SELECT * FROM catalog_master_event WHERE request_id=?",events,in.requestId()),after,false);
 }
 private ResponseStatusException fail(int status,String message){return new ResponseStatusException(HttpStatus.valueOf(status),message);}
}
