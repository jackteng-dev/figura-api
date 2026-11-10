package dev.jackteng.figura.service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
@Service
public class MockNotificationGateway {
 private final JdbcTemplate db;public MockNotificationGateway(JdbcTemplate db){this.db=db;}
 public static final class Failure extends RuntimeException {private final String code;public Failure(String code){super(code);this.code=code;}public String code(){return code;}}
 /** MOCK provider only: independent acceptance remains committed if the dispatch transaction fails. */
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public boolean accept(String key,String payload,String mode){
  String digest;try{digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
  var existing=db.queryForList("SELECT fingerprint FROM mock_notification_receipt WHERE delivery_key=?",String.class,key);
  if(!existing.isEmpty()){if(!existing.get(0).equals(digest))throw new IllegalStateException("Mock delivery payload mismatch");return true;}
  if("FAIL_BEFORE".equals(mode))throw new Failure("MOCK_UNAVAILABLE");
  db.update("INSERT INTO mock_notification_receipt(delivery_key,fingerprint) VALUES(?,?)",key,digest);return false;
 }
}
