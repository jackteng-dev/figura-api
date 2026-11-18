package dev.jackteng.figura.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.figura.dto.CatalogPayloads.Page;
import dev.jackteng.figura.dto.PreorderPayloads.*;
import dev.jackteng.figura.mapper.CatalogMapper;
import dev.jackteng.figura.mapper.FulfillmentMapper;
import dev.jackteng.figura.pojo.CustomUserDetails;
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
    private final PreorderOrderService orders; private final NotificationCapture notifications;
    public PreorderBatchService(JdbcTemplate db,ObjectMapper json,FulfillmentMapper auth,CatalogMapper catalog,CatalogMasterService masters,PreorderOrderService orders,NotificationCapture notifications){this.db=db;this.json=json;this.auth=auth;this.catalog=catalog;this.masters=masters;this.orders=orders;this.notifications=notifications;}
    private ResponseStatusException fail(int code,String message){return new ResponseStatusException(HttpStatus.valueOf(code),message);}
    private void uuid(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(Exception e){throw fail(400,"預購批次 ID 不正確");}}
    private void page(int page,int size){if(page<1||page>100000||size<1||size>50)throw fail(400,"分頁參數不正確");}
    private OffsetDateTime time(ResultSet r,String field)throws SQLException{LocalDateTime value=r.getObject(field,LocalDateTime.class);return value==null?null:value.atOffset(TAIPEI);}
    private LocalDateTime now(){return db.queryForObject("SELECT CURRENT_TIMESTAMP(6)",(r,n)->r.getObject(1,LocalDateTime.class));}
    private final RowMapper<Batch> batches=(r,n)->new Batch(r.getString("id"),r.getString("product_id"),r.getString("sku_id"),r.getString("name"),r.getString("description"),r.getString("product_title"),r.getString("sku_label"),r.getInt("price_twd"),r.getInt("capacity"),r.getInt("held_quantity"),r.getInt("paid_quantity"),time(r,"deadline_at"),r.getObject("arrival_from",LocalDate.class),r.getObject("arrival_to",LocalDate.class),r.getString("status"),r.getInt("version"),time(r,"opened_at"),time(r,"closed_at"),r.getString("payment_mode"),(Integer)r.getObject("deposit_twd"),(Integer)r.getObject("balance_days"),time(r,"balance_called_at"),r.getInt("received_quantity"));
    private final RowMapper<ScheduleEvent> schedules=(r,n)->new ScheduleEvent(r.getString("request_id"),r.getString("batch_id"),r.getInt("actor_id"),r.getString("actor_username"),r.getString("action"),r.getString("fingerprint"),r.getString("public_reason"),r.getString("note"),r.getObject("arrival_from_before",LocalDate.class),r.getObject("arrival_to_before",LocalDate.class),r.getObject("arrival_from_after",LocalDate.class),r.getObject("arrival_to_after",LocalDate.class),time(r,"balance_due_at"),r.getInt("affected_orders"),r.getInt("batch_version_before"),r.getInt("batch_version_after"),time(r,"created_at"));
    private final RowMapper<Event> events=(r,n)->new Event(r.getString("request_id"),r.getString("batch_id"),r.getInt("actor_id"),r.getString("actor_username"),r.getString("action"),r.getString("fingerprint"),r.getString("before_state"),r.getString("after_state"),r.getString("note"),time(r,"created_at"));
    private Batch find(String id,boolean lock){uuid(id);var found=db.query("SELECT * FROM preorder_batch WHERE id=?"+(lock?" FOR UPDATE":""),batches,id);if(found.isEmpty())throw fail(404,"找不到預購批次");return found.get(0);}
    @Transactional(readOnly=true) public Page<Batch> list(int page,int size){page(page,size);return new Page<>(db.query("SELECT * FROM preorder_batch ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",batches,size,(page-1)*size),page,size,db.queryForObject("SELECT COUNT(*) FROM preorder_batch",Long.class));}
    @Transactional(readOnly=true) public Detail detail(String id){var batch=find(id,false);return new Detail(batch,db.query("SELECT * FROM preorder_batch_event WHERE batch_id=? ORDER BY created_at,request_id",events,id),db.query("SELECT * FROM preorder_schedule_event WHERE batch_id=? ORDER BY created_at,request_id",schedules,id));}
    private PublicBatch publicView(Batch b,LocalDateTime now){String status=b.status();if(status.equals("OPEN")&&!b.deadlineAt().toLocalDateTime().isAfter(now))status="EXPIRED";return new PublicBatch(b.id(),b.productId(),b.name(),b.description(),b.productTitle(),b.skuLabel(),b.priceTwd(),b.capacity()-b.heldQuantity()-b.paidQuantity(),b.deadlineAt(),b.arrivalFrom(),b.arrivalTo(),status,status.equals("OPEN")&&b.capacity()>b.heldQuantity()+b.paidQuantity(),b.paymentMode(),b.depositTwd(),b.balanceDays());}
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
        String mode=in.mode();Integer deposit=in.depositTwd(),days=in.balanceDays();
        if(mode.equals("FULL")&&(deposit!=null||days!=null))throw fail(400,"全額付款批次不可設定訂金或尾款天數");
        if(mode.equals("DEPOSIT")&&(deposit==null||days==null||deposit>=in.priceTwd()))throw fail(400,"訂金批次需設定小於單價的每件訂金與尾款天數（1–60）");
        // 沒送付款條件時沿用 V22 前的 fingerprint，升級前未確認的原操作仍可用原 requestId／原內容核對
        var parts=new ArrayList<Object>(Arrays.asList(in.productId(),in.skuId(),name,description,in.priceTwd(),in.capacity(),normalized,in.arrivalFrom(),in.arrivalTo(),in.expectedVersion(),note));
        if(in.paymentMode()!=null||deposit!=null||days!=null)parts.addAll(Arrays.asList(mode,deposit,days));
        String hash=fingerprint(parts);var retry=replay(in.requestId(),id,action,hash,actor);if(retry!=null)return retry;
        var product=catalog.lock(in.productId());if(product==null)throw fail(404,"找不到商品");if("ARCHIVED".equals(product.getStatus()))throw fail(409,"封存商品不能建立或編輯預購批次");
        var sku=catalog.skus(product.getId()).stream().filter(s->s.getId().equals(in.skuId())).findFirst().orElseThrow(()->fail(409,"SKU 不屬於此商品"));if(!sku.isEnabled())throw fail(409,"預購需要啟用的 SKU");
        Batch before=null;if(id==null){if(in.expectedVersion()!=0)throw fail(409,"新批次版本需為 0");id=UUID.randomUUID().toString();}else{before=find(id,true);version(before,in.expectedVersion());if(!before.status().equals("DRAFT"))throw fail(409,"只有草稿批次可編輯");if(!before.productId().equals(in.productId())||!before.skuId().equals(in.skuId()))throw fail(409,"批次商品與 SKU 不可更換，請建立新批次");}
        var cutoff=deadline(in);
        if(before==null)db.update("INSERT INTO preorder_batch(id,product_id,sku_id,name,description,product_title,sku_label,price_twd,capacity,deadline_at,arrival_from,arrival_to,payment_mode,deposit_twd,balance_days) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,in.productId(),in.skuId(),name,description,product.getTitle(),sku.getLabel(),in.priceTwd(),in.capacity(),cutoff,in.arrivalFrom(),in.arrivalTo(),mode,deposit,days);
        else db.update("UPDATE preorder_batch SET name=?,description=?,product_title=?,sku_label=?,price_twd=?,capacity=?,deadline_at=?,arrival_from=?,arrival_to=?,payment_mode=?,deposit_twd=?,balance_days=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",name,description,product.getTitle(),sku.getLabel(),in.priceTwd(),in.capacity(),cutoff,in.arrivalFrom(),in.arrivalTo(),mode,deposit,days,id);
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

    // ---- Phase 4：時程與尾款。鎖序 actor/grant → 受影響顧客（依 ID）→ batch → 依 ID 排序的訂單。
    // 付款／取消／退款都是 customer → batch → order，下單是 customer → master → product → batch；
    // outbox 的 recipient 外鍵會對 customer 取共享鎖，所以顧客必須在 batch 之前先鎖，且這裡不取 master guard（避免與下單反向）。
    private String authorizeOnly(CustomUserDetails actor,String permission){String username=actor==null?null:auth.lockActor(actor.getId());if(username==null||auth.lockPermission(actor.getId(),permission).isEmpty())throw fail(403,"員工或預購授權已失效");return username;}
    private Set<String> lockCustomers(String batch,String where){var ids=db.queryForList("SELECT DISTINCT customer_id FROM preorder_order WHERE batch_id=? AND "+where+" ORDER BY customer_id",String.class,batch);for(var id:ids)db.queryForList("SELECT id FROM customer WHERE id=? FOR UPDATE",String.class,id);return new HashSet<>(ids);}
    private void covered(List<Target> targets,Set<String> locked){if(targets.stream().anyMatch(t->!locked.contains(t.customer())))throw fail(409,"批次訂單剛有變動，請重讀後再操作");}
    private ScheduleApplied scheduleReplay(String request,String id,String action,String hash,CustomUserDetails actor){var old=db.query("SELECT * FROM preorder_schedule_event WHERE request_id=?",schedules,request);if(old.isEmpty())return null;var e=old.get(0);if(e.actorId()!=actor.getId()||!e.batchId().equals(id)||!e.action().equals(action)&&!(Set.of("DELAY","ADJUST").contains(e.action())&&action.equals("SCHEDULE"))||!e.fingerprint().equals(hash))throw fail(409,"requestId 已用於不同預購操作");return new ScheduleApplied(e,find(id,false),true);}
    private record Target(String id,String customer) {}
    private List<Target> lockOrders(String batch,String where){return db.query("SELECT id,customer_id FROM preorder_order WHERE batch_id=? AND "+where+" ORDER BY id FOR UPDATE",(r,n)->new Target(r.getString(1),r.getString(2)),batch);}
    private String source(String request,String order){return UUID.nameUUIDFromBytes((request+"|"+order).getBytes(StandardCharsets.UTF_8)).toString();}
    private ScheduleApplied record(String request,Batch before,String action,String hash,String reason,String note,LocalDateTime due,int affected,CustomUserDetails actor,String username){
        var after=find(before.id(),false);
        try{db.update("INSERT INTO preorder_schedule_event(request_id,batch_id,actor_id,actor_username,action,fingerprint,public_reason,note,arrival_from_before,arrival_to_before,arrival_from_after,arrival_to_after,balance_due_at,affected_orders,batch_version_before,batch_version_after) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",request,before.id(),actor.getId(),username,action,hash,reason,note,before.arrivalFrom(),before.arrivalTo(),after.arrivalFrom(),after.arrivalTo(),due,affected,before.version(),after.version());}
        catch(org.springframework.dao.DuplicateKeyException e){throw fail(409,"requestId 已用於其他預購操作，請重讀核對");}
        return new ScheduleApplied(db.queryForObject("SELECT * FROM preorder_schedule_event WHERE request_id=?",schedules,request),after,false);
    }
    /** 延期或調整預估到貨：更新批次與尚未到貨的未結束訂單，保留前後紀錄並通知受影響顧客。 */
    @Transactional(isolation=Isolation.READ_COMMITTED) public ScheduleApplied schedule(String id,Schedule in,CustomUserDetails actor){
        uuid(id);String username=authorizeOnly(actor,"preorders.schedule"),reason=in.publicReason().trim(),note=in.note().trim();if(reason.isBlank()||note.isBlank())throw fail(400,"顧客說明與內部原因不可空白");
        String hash=fingerprint(List.of("SCHEDULE",id,in.expectedVersion(),in.arrivalFrom(),in.arrivalTo(),reason,note));var retry=scheduleReplay(in.requestId(),id,"SCHEDULE",hash,actor);if(retry!=null)return retry;
        String open="status IN ('PENDING','PAID') AND arrival_status='NOT_ARRIVED'";var locked=lockCustomers(id,open);
        var batch=find(id,true);version(batch,in.expectedVersion());
        if(batch.status().equals("DRAFT"))throw fail(409,"草稿批次請直接編輯到貨區間");
        if(in.arrivalFrom().isAfter(in.arrivalTo())||in.arrivalTo().getYear()>2100||batch.deadlineAt().toLocalDate().isAfter(in.arrivalFrom()))throw fail(400,"到貨區間不正確，最早到貨不可早於截單日");
        if(in.arrivalFrom().equals(batch.arrivalFrom())&&in.arrivalTo().equals(batch.arrivalTo()))throw fail(409,"到貨區間沒有變更");
        String action=in.arrivalTo().isAfter(batch.arrivalTo())||in.arrivalFrom().isAfter(batch.arrivalFrom())?"DELAY":"ADJUST";
        db.update("UPDATE preorder_batch SET arrival_from=?,arrival_to=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",in.arrivalFrom(),in.arrivalTo(),id);
        var targets=lockOrders(id,open);covered(targets,locked);
        for(var t:targets){db.update("UPDATE preorder_order SET arrival_from=?,arrival_to=?,version=version+1 WHERE id=?",in.arrivalFrom(),in.arrivalTo(),t.id());notifications.enqueuePreorder(t.customer(),orders.detail(t.customer(),t.id()),action.equals("DELAY")?"PREORDER_DELAYED":"PREORDER_RESCHEDULED",source(in.requestId(),t.id()));}
        return record(in.requestId(),batch,action,hash,reason,note,null,targets.size(),actor,username);
    }
    /** 到貨後通知訂金訂單付尾款：已付訂金、尚未通知的訂單改為待付尾款並設期限，之後付訂金的訂單自動跟進。 */
    @Transactional(isolation=Isolation.READ_COMMITTED) public ScheduleApplied balanceCall(String id,BalanceCall in,CustomUserDetails actor){
        uuid(id);String username=authorizeOnly(actor,"preorders.balance.call"),reason=in.publicReason().trim(),note=in.note().trim();if(reason.isBlank()||note.isBlank())throw fail(400,"顧客說明與內部原因不可空白");
        String hash=fingerprint(List.of("BALANCE_CALL",id,in.expectedVersion(),reason,note));var retry=scheduleReplay(in.requestId(),id,"BALANCE_CALL",hash,actor);if(retry!=null)return retry;
        String due="status='PAID' AND payment_mode='DEPOSIT' AND balance_status='NOT_DUE'";var locked=lockCustomers(id,due);
        var batch=find(id,true);version(batch,in.expectedVersion());
        if(!"DEPOSIT".equals(batch.paymentMode()))throw fail(409,"只有訂金批次需要通知尾款");
        if(batch.receivedQuantity()<1)throw fail(409,"批次登記到貨後才能通知尾款");
        var dueAt=now().plusDays(batch.balanceDays());
        var targets=lockOrders(id,due);covered(targets,locked);
        if(targets.isEmpty())throw fail(409,"沒有需要通知尾款的已付訂金訂單");
        db.update("UPDATE preorder_batch SET balance_called_at=COALESCE(balance_called_at,CURRENT_TIMESTAMP(6)),version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);
        for(var t:targets){db.update("UPDATE preorder_order SET balance_status='DUE',balance_due_at=?,version=version+1 WHERE id=?",dueAt,t.id());notifications.enqueuePreorder(t.customer(),orders.detail(t.customer(),t.id()),"PREORDER_BALANCE_DUE",source(in.requestId(),t.id()));}
        return record(in.requestId(),batch,"BALANCE_CALL",hash,reason,note,dueAt,targets.size(),actor,username);
    }
}
