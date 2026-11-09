package com.shop.elpsy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.elpsy.dto.AccessPayloads.*;
import com.shop.elpsy.dto.CatalogPayloads.Page;
import com.shop.elpsy.pojo.CustomUserDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Guard -> sorted staff rows -> grants. Business writes already lock staff before grants. */
@Service
public class StaffAccessService {
 private final JdbcTemplate db; private final ObjectMapper json; private final BCryptPasswordEncoder passwords; private final byte[] key;
 public StaffAccessService(JdbcTemplate db,ObjectMapper json,BCryptPasswordEncoder passwords,@Value("${auth.jwt-secret}") String secret){this.db=db;this.json=json;this.passwords=passwords;key=secret.getBytes(StandardCharsets.UTF_8);}
 private List<Integer> ids(String sql,int id){return db.queryForList(sql,Integer.class,id);}
 private final RowMapper<Event> eventMapper=(r,n)->new Event(r.getString("request_id"),r.getInt("actor_id"),r.getString("actor_username"),r.getString("action"),r.getInt("target_id"),r.getString("fingerprint"),r.getString("before_state"),r.getString("after_state"),r.getString("note"),r.getObject("created_at",java.time.LocalDateTime.class));
 private Staff staff(int id){var rows=db.query("SELECT id,username,is_stop,access_version FROM user WHERE id=?",(r,n)->new Staff(r.getInt(1),r.getString(2),r.getString(3).equals("F"),r.getInt(4),ids("SELECT role_id FROM user_role WHERE user_id=? ORDER BY role_id",id)),id);if(rows.isEmpty())throw fail(404,"員工不存在");return rows.get(0);}
 private Role role(int id){var rows=db.query("SELECT id,name,description,enabled,version FROM role WHERE id=?",(r,n)->new Role(r.getInt(1),r.getString(2),Objects.requireNonNullElse(r.getString(3),""),r.getBoolean(4),r.getInt(5),ids("SELECT permission_id FROM role_permission WHERE role_id=? ORDER BY permission_id",id)),id);if(rows.isEmpty())throw fail(404,"角色不存在");return rows.get(0);}
 private void page(int p,int size){if(p<1||p>100000||size<1||size>50)throw fail(400,"頁數或每頁筆數不正確");}
 public Page<Staff> staffList(int p,int size){page(p,size);return new Page<>(db.queryForList("SELECT id FROM user ORDER BY id LIMIT ? OFFSET ?",Integer.class,size,(p-1)*size).stream().map(this::staff).toList(),p,size,db.queryForObject("SELECT COUNT(*) FROM user",Long.class));}
 public Page<Role> roles(int p,int size){page(p,size);return new Page<>(db.queryForList("SELECT id FROM role ORDER BY id LIMIT ? OFFSET ?",Integer.class,size,(p-1)*size).stream().map(this::role).toList(),p,size,db.queryForObject("SELECT COUNT(*) FROM role",Long.class));}
 public List<Capability> permissions(){return db.query("SELECT id,code,name,description FROM permissions_backend WHERE code IS NOT NULL ORDER BY id",(r,n)->new Capability(r.getInt(1),r.getString(2),r.getString(3),r.getString(4)));}
 public Page<Event> audit(int p,int size){page(p,size);return new Page<>(db.query("SELECT * FROM staff_access_event ORDER BY created_at DESC,request_id DESC LIMIT ? OFFSET ?",eventMapper,size,(p-1)*size),p,size,db.queryForObject("SELECT COUNT(*) FROM staff_access_event",Long.class));}
 private String lock(CustomUserDetails actor){
   db.queryForObject("SELECT id FROM staff_access_guard WHERE id=1 FOR UPDATE",Integer.class);
   // Conservative global roster locking serializes governance and prevents user/grant reverse lock order.
   db.queryForList("SELECT id FROM user ORDER BY id FOR UPDATE",Integer.class);
   var u=staff(actor.getId());if(!u.enabled()||!manager(u.id()))throw fail(403,"需要完整存取管理權限");return u.username();
 }
 private boolean manager(int id){return db.queryForObject("SELECT COUNT(DISTINCT p.id) FROM user_role ur JOIN role r ON r.id=ur.role_id AND r.enabled=TRUE JOIN role_permission rp ON rp.role_id=r.id JOIN permissions_backend p ON p.id=rp.permission_id WHERE ur.user_id=? AND p.id BETWEEN 100 AND 107",Integer.class,id)==8;}
 private void protect(){boolean any=db.queryForList("SELECT id FROM user WHERE is_stop='F' ORDER BY id",Integer.class).stream().anyMatch(this::manager);if(!any)throw fail(409,"必須保留至少一位啟用且具完整存取管理權限的管理員");}
 private List<Integer> sorted(List<Integer> input){var out=new TreeSet<>(input);if(out.size()!=input.size())throw fail(400,"不可重複指派");return List.copyOf(out);}
 private void validateRoles(List<Integer> roles){for(int id:roles)role(id);}
 private void validatePermissions(List<Integer> permissions){var allowed=this.permissions().stream().map(Capability::id).collect(java.util.stream.Collectors.toSet());if(!allowed.containsAll(permissions))throw fail(400,"只可指派固定權限目錄內的權限");}
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("無法產生存取稽核",e);}}
 private String digest(Object value){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(encode(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 private Applied replay(String request,int actor,String action,int target,String fingerprint){var found=db.query("SELECT * FROM staff_access_event WHERE request_id=?",eventMapper,request);if(found.isEmpty())return null;var e=found.get(0);if(e.actorId()!=actor||!e.action().equals(action)||(target!=0&&e.targetId()!=target)||!e.fingerprint().equals(fingerprint))throw fail(409,"requestId 已用於不同操作");return new Applied(e,true);}
 private Applied append(String request,int actor,String username,String action,int target,String fingerprint,Object before,Object after,String note){protect();db.update("INSERT INTO staff_access_event(request_id,actor_id,actor_username,action,target_id,fingerprint,before_state,after_state,note) VALUES(?,?,?,?,?,?,?,?,?)",request,actor,username,action,target,fingerprint,before==null?null:encode(before),encode(after),note);return new Applied(db.queryForObject("SELECT * FROM staff_access_event WHERE request_id=?",eventMapper,request),false);}
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
 public Applied createStaff(StaffCreate in,CustomUserDetails actor){
   if(in.password().getBytes(StandardCharsets.UTF_8).length>72)throw fail(400,"密碼UTF-8長度不可超過72 bytes");
   var roles=sorted(in.roleIds());String note=in.note().trim(),fp=digest(List.of(in.username(),in.password(),in.enabled(),roles,note));String username=lock(actor);
   var replay=replay(in.requestId(),actor.getId(),"STAFF_CREATE",0,fp);if(replay!=null)return replay;validateRoles(roles);
   try{db.update("INSERT INTO user(username,password,is_stop,created_time,modified_time) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",in.username(),passwords.encode(in.password()),in.enabled()?"F":"T");}catch(DuplicateKeyException e){throw fail(409,"帳號名稱已存在");}
   int id=db.queryForObject("SELECT id FROM user WHERE username=?",Integer.class,in.username());for(int role:roles)db.update("INSERT INTO user_role VALUES(?,?)",id,role);
   return append(in.requestId(),actor.getId(),username,"STAFF_CREATE",id,fp,null,staff(id),note);
 }
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
 public Applied updateStaff(int id,StaffWrite in,CustomUserDetails actor){
   var roles=sorted(in.roleIds());String note=in.note().trim(),fp=digest(List.of(in.enabled(),roles,in.expectedVersion(),note));String username=lock(actor);
   var replay=replay(in.requestId(),actor.getId(),"STAFF_UPDATE",id,fp);if(replay!=null)return replay;var before=staff(id);if(before.version()!=in.expectedVersion())throw fail(409,"員工版本已變更，請先重讀");validateRoles(roles);
   boolean security=before.enabled()!=in.enabled()||!before.roleIds().equals(roles);
   db.update("UPDATE user SET is_stop=?,access_version=access_version+1,auth_version=auth_version+?,modified_time=CURRENT_TIMESTAMP WHERE id=?",in.enabled()?"F":"T",security?1:0,id);
   db.update("DELETE FROM user_role WHERE user_id=?",id);for(int role:roles)db.update("INSERT INTO user_role VALUES(?,?)",id,role);
   return append(in.requestId(),actor.getId(),username,"STAFF_UPDATE",id,fp,before,staff(id),note);
 }
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
 public Applied saveRole(Integer id,RoleWrite in,CustomUserDetails actor){
   var perms=sorted(in.permissionIds());String note=in.note().trim(),name=in.name().trim(),desc=in.description().trim();if(name.isBlank()||note.isBlank())throw fail(400,"名稱與說明不可空白");
   String action=id==null?"ROLE_CREATE":"ROLE_UPDATE",fp=digest(List.of(name,desc,in.enabled(),perms,in.expectedVersion(),note));String username=lock(actor);
   var replay=replay(in.requestId(),actor.getId(),action,id==null?0:id,fp);if(replay!=null)return replay;validatePermissions(perms);Role before=null;
   if(id==null){if(in.expectedVersion()!=0)throw fail(409,"新角色版本需為0");id=db.queryForObject("SELECT COALESCE(MAX(id),0)+1 FROM role",Integer.class);db.update("INSERT INTO role(id,name,description,enabled) VALUES(?,?,?,?)",id,name,desc,in.enabled());}
   else{before=role(id);if(before.version()!=in.expectedVersion())throw fail(409,"角色版本已變更，請先重讀");db.update("UPDATE role SET name=?,description=?,enabled=?,version=version+1 WHERE id=?",name,desc,in.enabled(),id);
     if(before.enabled()!=in.enabled()||!before.permissionIds().equals(perms))db.update("UPDATE user u JOIN user_role ur ON ur.user_id=u.id SET u.auth_version=u.auth_version+1,u.access_version=u.access_version+1 WHERE ur.role_id=?",id);
   }
   db.update("DELETE FROM role_permission WHERE role_id=?",id);for(int permission:perms)db.update("INSERT INTO role_permission VALUES(?,?)",id,permission);
   return append(in.requestId(),actor.getId(),username,action,id,fp,before,role(id),note);
 }
 private ResponseStatusException fail(int status,String message){return new ResponseStatusException(HttpStatus.valueOf(status),message);}
}
