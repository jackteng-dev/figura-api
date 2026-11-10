package dev.jackteng.figura.service;
import dev.jackteng.figura.dto.NotificationPayloads.*;
import dev.jackteng.figura.dto.CatalogPayloads.Page;
import dev.jackteng.figura.pojo.CustomUserDetails;
import dev.jackteng.figura.mapper.FulfillmentMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;
@Service
public class NotificationService {
 private final JdbcTemplate db;private final FulfillmentMapper auth;private final MockNotificationGateway gateway;private final boolean enabled;
 public NotificationService(JdbcTemplate db,FulfillmentMapper auth,MockNotificationGateway gateway,@Value("${shop.notifications.mock-enabled:false}") boolean enabled){this.db=db;this.auth=auth;this.gateway=gateway;this.enabled=enabled;}
 private record Box(String id,String kind,String order,String payload,String status,int version,int attempts,int cycle,LocalDateTime next,String error,LocalDateTime sent,LocalDateTime created) {}
 private LocalDateTime time(java.sql.ResultSet r,String c)throws java.sql.SQLException{return r.getObject(c,LocalDateTime.class);}
 private final RowMapper<Box> boxes=(r,n)->new Box(r.getString("id"),r.getString("event_kind"),(r.getString("order_id")!=null?r.getString("order_id"):r.getString("preorder_order_id")),r.getString("payload"),r.getString("status"),r.getInt("version"),r.getInt("attempts"),r.getInt("cycle_attempts"),time(r,"next_attempt_at"),r.getString("last_error"),time(r,"sent_at"),time(r,"created_at"));
 private final RowMapper<Audit> audits=(r,n)->new Audit(r.getString("request_id"),r.getString("notification_id"),r.getObject("actor_id",Integer.class),r.getString("actor_username"),r.getString("action"),r.getString("mode"),r.getString("note"),r.getInt("expected_version"),r.getString("before_status"),r.getString("after_status"),r.getInt("after_version"),r.getInt("attempt_number"),r.getString("result"),time(r,"created_at"));
 private ResponseStatusException fail(int status,String message){return new ResponseStatusException(HttpStatus.valueOf(status),message);}
 private void uuid(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(Exception e){throw fail(400,"通知或訂單ID不正確");}}
 private Box box(String id,boolean lock){uuid(id);var rows=db.query("SELECT * FROM notification_outbox WHERE id=?"+(lock?" FOR UPDATE":""),boxes,id);if(rows.isEmpty())throw fail(404,"找不到通知");return rows.get(0);}
 private LocalDateTime now(){return db.queryForObject("SELECT CURRENT_TIMESTAMP(6)",LocalDateTime.class);}
 private boolean due(Box b){return Set.of("PENDING","RETRY").contains(b.status())&&b.cycle()<5&&!b.next().isAfter(now());}
 private Message view(Box b){return new Message(b.id(),b.kind(),b.order(),"MOCK",b.status(),b.version(),b.attempts(),b.cycle(),b.next(),b.error(),b.sent(),b.created(),enabled&&due(b),Set.of("RETRY","DEAD").contains(b.status()),db.queryForObject("SELECT COUNT(*) FROM mock_notification_receipt WHERE delivery_key=?",Integer.class,b.id())==1);}
 @Transactional(readOnly=true) public Page<Message> list(int page,int size,String status,String order){
  if(page<1||page>100000||size<1||size>50)throw fail(400,"分頁範圍不正確");if(status!=null&&!Set.of("PENDING","RETRY","SENT","DEAD").contains(status))throw fail(400,"通知狀態不正確");if(order!=null)uuid(order);
  String where=" WHERE (? IS NULL OR status=?) AND (? IS NULL OR COALESCE(order_id,preorder_order_id)=?)";return new Page<>(db.query("SELECT * FROM notification_outbox"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",boxes,status,status,order,order,size,(page-1)*size).stream().map(this::view).toList(),page,size,db.queryForObject("SELECT COUNT(*) FROM notification_outbox"+where,Long.class,status,status,order,order));
 }
 @Transactional(readOnly=true) public Detail detail(String id){var b=box(id,false);return new Detail(view(b),b.payload(),db.query("SELECT * FROM notification_event WHERE notification_id=? ORDER BY created_at,request_id",audits,id),enabled);}
 private String authorize(CustomUserDetails actor,String code){String name=actor==null?null:auth.lockActor(actor.getId());if(name==null||auth.lockPermission(actor.getId(),code).isEmpty())throw fail(403,"員工或通知授權已失效");return name;}
 private void reserve(Box b,String request,Integer actor,String username,String action,String mode,String note,int version){
  // Reserve the globally unique command before the independently committed MOCK acceptance.
  try{db.update("INSERT INTO notification_event(request_id,notification_id,actor_id,actor_username,action,mode,note,expected_version,before_status,after_status,after_version,attempt_number,result) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",request,b.id(),actor,username,action,mode,note,version,b.status(),b.status(),b.version(),b.attempts(),"STARTED");}catch(DuplicateKeyException e){throw fail(409,"操作ID已被其他通知使用");}
 }
 private Audit event(String request){return db.queryForObject("SELECT * FROM notification_event WHERE request_id=?",audits,request);}
 private Applied prior(Box b,String request,int actor,String action,String mode,String note,int version){var rows=db.query("SELECT * FROM notification_event WHERE request_id=?",audits,request);if(rows.isEmpty())return null;var e=rows.get(0);if(!Objects.equals(e.actorId(),actor)||!e.notificationId().equals(b.id())||!e.action().equals(action)||!Objects.equals(e.mode(),mode)||!e.note().equals(note)||e.expectedVersion()!=version)throw fail(409,"操作ID已用於不同通知內容");return new Applied(e,view(b),true);}
 private void finish(Box b,String request,String result){db.update("UPDATE notification_event SET after_status=?,after_version=?,attempt_number=?,result=? WHERE request_id=?",b.status(),b.version(),b.attempts(),result,request);}
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Applied retry(String id,Change in,CustomUserDetails actor){
  String username=authorize(actor,"notifications.retry"),note=in.note().trim();var b=box(id,true);var old=prior(b,in.requestId(),actor.getId(),"RETRY",null,note,in.expectedVersion());if(old!=null)return old;
  if(b.version()!=in.expectedVersion())throw fail(409,"通知版本已變更，請先重讀");if(!Set.of("RETRY","DEAD").contains(b.status()))throw fail(409,"只有失敗通知可重新排程");
  reserve(b,in.requestId(),actor.getId(),username,"RETRY",null,note,in.expectedVersion());db.update("UPDATE notification_outbox SET status='PENDING',cycle_attempts=0,next_attempt_at=CURRENT_TIMESTAMP(6),last_error=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);var current=box(id,false);finish(current,in.requestId(),"REQUEUED");return new Applied(event(in.requestId()),view(current),false);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Applied dispatch(String id,Dispatch in,CustomUserDetails actor){
  if(!enabled)throw fail(404,"模擬通知派送未啟用");String username=authorize(actor,"notifications.dispatch.mock"),note=in.note().trim();var b=box(id,true);var old=prior(b,in.requestId(),actor.getId(),"DISPATCH",in.mode(),note,in.expectedVersion());if(old!=null)return old;
  if(b.version()!=in.expectedVersion())throw fail(409,"通知版本已變更，請先重讀");if(!due(b))throw fail(409,"通知尚未到重試時間或已完成，請先核對");reserve(b,in.requestId(),actor.getId(),username,"DISPATCH",in.mode(),note,in.expectedVersion());return attempt(b,in.requestId(),in.mode());
 }
 private Applied attempt(Box b,String request,String mode){
  String error=null,result;boolean replay=false;
  try{replay=gateway.accept(b.id(),b.payload(),mode);if("FAIL_AFTER_ACCEPT".equals(mode)&&!replay)throw new MockNotificationGateway.Failure("MOCK_RESULT_UNKNOWN");result=replay?"ACCEPTED_REPLAY":"ACCEPTED";}catch(MockNotificationGateway.Failure e){error=e.code();result=error;}
  int cycle=b.cycle()+1;String state=error==null?"SENT":cycle>=5?"DEAD":"RETRY";LocalDateTime at=now();LocalDateTime next=state.equals("RETRY")?at.plusSeconds(Math.min(60,5L<<(cycle-1))):null;
  db.update("UPDATE notification_outbox SET status=?,attempts=attempts+1,cycle_attempts=?,version=version+1,next_attempt_at=?,last_error=?,sent_at=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",state,cycle,next,error,state.equals("SENT")?at:null,b.id());var current=box(b.id(),false);finish(current,request,result);return new Applied(event(request),view(current),false);
 }
 /** One short DB transaction per MOCK attempt. No socket I/O or business resource locks. */
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Applied runOne(){
  if(!enabled)return null;var rows=db.query("SELECT * FROM notification_outbox WHERE status IN ('PENDING','RETRY') AND next_attempt_at<=CURRENT_TIMESTAMP(6) AND cycle_attempts<5 ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",boxes);if(rows.isEmpty())return null;var b=rows.get(0);String request=UUID.randomUUID().toString();reserve(b,request,null,"SYSTEM","DISPATCH","SUCCESS","自動 mock 派送",b.version());return attempt(b,request,"SUCCESS");
 }
}
