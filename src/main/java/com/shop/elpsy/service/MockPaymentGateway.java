package com.shop.elpsy.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.elpsy.dto.PaymentPayloads.Notification;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
@Component
public class MockPaymentGateway {
 private final boolean enabled;private final String secret;private final ObjectMapper json;private final Validator validator;
 public MockPaymentGateway(@Value("${shop.payments.mock-enabled:false}") boolean enabled,@Value("${shop.payments.mock-secret:}") String secret,ObjectMapper json,Validator validator){this.enabled=enabled;this.secret=secret;this.json=json;this.validator=validator;}
 @PostConstruct void validateConfiguration(){if(enabled&&secret.length()<32)throw new IllegalStateException("Enabled mock payments require a private webhook secret of at least 32 characters");}
 public boolean enabled(){return enabled;}
 public void requireEnabled(){if(!enabled)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"付款模擬未啟用");}
 public String sign(String timestamp,String raw){
  requireEnabled();try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal((timestamp+"."+raw).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Webhook signing unavailable");}
 }
 public Notification verify(String timestamp,String signature,String raw){
  requireEnabled();if(raw==null||raw.length()>8192)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"通知過大");
  if(timestamp==null||!timestamp.matches("[0-9]{10}")||Math.abs(Instant.now().getEpochSecond()-Long.parseLong(timestamp))>300||signature==null||!signature.matches("[0-9a-f]{64}")||!MessageDigest.isEqual(sign(timestamp,raw).getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"付款通知驗證失敗");
  try{var input=json.readValue(raw,Notification.class);if(input==null||!validator.validate(input).isEmpty())throw new IllegalArgumentException();return input;}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"付款通知格式不正確");}
 }
 public String raw(Notification input){try{return json.writeValueAsString(input);}catch(Exception e){throw new IllegalStateException("Cannot serialize mock notification");}}
 public String fingerprint(Notification input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw(input).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Cannot fingerprint notification");}}
}
