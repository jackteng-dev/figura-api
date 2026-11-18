package dev.jackteng.figura.service;

import dev.jackteng.figura.dto.CustomerPayloads;
import dev.jackteng.figura.mapper.CustomerMapper;
import dev.jackteng.figura.pojo.Customer;
import dev.jackteng.figura.utils.JwtUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class CustomerSessionService {
    public static final String ACCESS_COOKIE="customerAccessToken", REFRESH_COOKIE="customerRefreshToken";
    private final CustomerMapper customers;
    private final BCryptPasswordEncoder passwords;
    private final StringRedisTemplate redis;
    private final JwtUtil jwt;
    private static final DefaultRedisScript<Long> ROTATE=new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1])==ARGV[1] then redis.call('SET',KEYS[1],ARGV[2],'EX',ARGV[3]); return 1 end; return 0",Long.class);
    private static final DefaultRedisScript<Long> REVOKE=new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end; return 0",Long.class);
    public CustomerSessionService(CustomerMapper customers, BCryptPasswordEncoder passwords, StringRedisTemplate redis, JwtUtil jwt) {
        this.customers=customers;this.passwords=passwords;this.redis=redis;this.jwt=jwt;
    }
    public Customer register(CustomerPayloads.Registration input) {
        var customer=new Customer();customer.setId(UUID.randomUUID().toString());
        customer.setPasswordHash(passwords.encode(input.password()));
        // 手機＋Email＋密碼。username／display_name 欄位仍為資料庫必填（保留 schema 相容），由系統產生內部識別值，不對外、不能登入。
        String email=CustomerPayloads.normalizeEmail(input.email()), phone=CustomerPayloads.normalizePhone(input.phone());
        if (customers.byEmail(email)!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"此 Email 已被註冊");
        if (customers.byPhone(phone)!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"此手機號碼已被註冊");
        customer.setEmail(email);customer.setPhone(phone);
        customer.setUsername("m_"+UUID.randomUUID().toString().replace("-","").substring(0,24));
        customer.setDisplayName("會員"+phone.substring(phone.length()-4));
        try { customers.insert(customer); }
        catch (DuplicateKeyException e) {
            // 同時註冊的競爭：以違反的唯一鍵判斷訊息
            String m=String.valueOf(e.getMostSpecificCause().getMessage());
            if (m.contains("uk_customer_email")) throw new ResponseStatusException(HttpStatus.CONFLICT,"此 Email 已被註冊");
            if (m.contains("uk_customer_phone")) throw new ResponseStatusException(HttpStatus.CONFLICT,"此手機號碼已被註冊");
            throw new ResponseStatusException(HttpStatus.CONFLICT,"帳號已被使用");
        }
        return customers.byId(customer.getId());
    }
    /** 依登入識別找顧客：只接受 Email 或台灣手機；帳號名稱登入已停用。 */
    private Customer byLoginIdentifier(String raw) {
        String value=raw.strip();
        if (value.contains("@")) { String email=CustomerPayloads.normalizeEmail(value); return email==null?null:customers.byEmail(email); }
        String phone=CustomerPayloads.normalizePhone(value);
        return phone==null?null:customers.byPhone(phone);
    }
    /**
     * 登入與重設密碼競爭：登入先讀到舊密碼雜湊並驗證通過，若在 revokeAll() 之後才寫入 session，舊密碼會留下有效登入。
     * 寫入 session 後重讀雜湊；已變更就撤回剛寫入的 session 並視為登入失敗。
     */
    private void ensurePasswordUnchanged(Customer verified,Map<String,Object> claims) {
        var current=customers.byId(verified.getId());
        if (current==null || !current.isEnabled() || !Objects.equals(current.getPasswordHash(),verified.getPasswordHash())) {
            redis.execute(REVOKE,List.of(key(claims)),claims.get("jti"));
            throw badCredentials();
        }
    }
    /** 撤銷此顧客所有網站與 App 登入（重設密碼後使用）。 */
    public void revokeAll(String customerId) {
        for (String prefix : List.of("shop:customer:session:","shop:customer:mobile:session:")) {
            var keys=redis.keys(prefix+customerId+":*");
            if (keys!=null && !keys.isEmpty()) redis.delete(keys);
        }
    }
    public CustomerPayloads.View login(CustomerPayloads.Login input,HttpServletResponse response) {
        var customer=byLoginIdentifier(input.username());
        if (customer==null || !customer.isEnabled() || !passwords.matches(input.password(),customer.getPasswordHash())) throw badCredentials();
        var claims=claims(customer,input.deviceId(),UUID.randomUUID().toString(),input.rememberMe());
        redis.opsForValue().set(key(claims),(String)claims.get("jti"),JwtUtil.REFRESH_LIFETIME);
        ensurePasswordUnchanged(customer,claims);
        writeCookies(response,claims);
        return CustomerPayloads.View.from(customer);
    }
    public Customer authenticate(String token) {
        var claims=verified(token,"access");
        if (!claims.get("jti").equals(redis.opsForValue().get(key(claims)))) throw unauthorized();
        return activeCustomer((String)claims.get("id"));
    }
    // Native tokens never travel in website cookies and use a separate Redis/realm boundary.
    public dev.jackteng.figura.dto.MobileAuthPayloads.Tokens mobileLogin(CustomerPayloads.Login input) {
        var customer=byLoginIdentifier(input.username());
        if(customer==null || !customer.isEnabled() || !passwords.matches(input.password(),customer.getPasswordHash()))throw badCredentials();
        var claims=mobileClaims(customer,input.deviceId());
        redis.opsForValue().set(key(claims),(String)claims.get("jti"),JwtUtil.REFRESH_LIFETIME);
        ensurePasswordUnchanged(customer,claims);
        return tokens(customer,claims);
    }
    public Customer mobileAuthenticate(String token) {
        var claims=verified(token,"access","customer_app");
        if(!claims.get("jti").equals(redis.opsForValue().get(key(claims))))throw unauthorized();
        return activeCustomer((String)claims.get("id"));
    }
    public dev.jackteng.figura.dto.MobileAuthPayloads.Tokens mobileRefresh(String token) {
        var old=verified(token,"refresh","customer_app");var customer=activeCustomer((String)old.get("id"));
        var next=mobileClaims(customer,(String)old.get("deviceId"));
        var result=redis.execute(ROTATE,List.of(key(old)),old.get("jti"),next.get("jti"),String.valueOf(JwtUtil.REFRESH_LIFETIME.toSeconds()));
        if(!Long.valueOf(1).equals(result))throw unauthorized();return tokens(customer,next);
    }
    public void mobileLogout(String token) {
        var claims=verified(token,"refresh","customer_app");
        redis.execute(REVOKE,List.of(key(claims)),claims.get("jti"));
    }
    private Map<String,Object> mobileClaims(Customer customer,String device) {
        return Map.of("id",customer.getId(),"deviceId",device,"jti",UUID.randomUUID().toString(),"realm","customer_app");
    }
    private dev.jackteng.figura.dto.MobileAuthPayloads.Tokens tokens(Customer customer,Map<String,Object> claims) {
        return new dev.jackteng.figura.dto.MobileAuthPayloads.Tokens(jwt.genAccessToken(claims),jwt.genRefreshToken(claims),
                JwtUtil.ACCESS_LIFETIME.toSeconds(),CustomerPayloads.View.from(customer));
    }
    public CustomerPayloads.View ownProfile(String authenticatedId) { return CustomerPayloads.View.from(activeCustomer(authenticatedId)); }
    public void refresh(String token,HttpServletResponse response) {
        var old=verified(token,"refresh");var customer=activeCustomer((String)old.get("id"));
        var next=claims(customer,(String)old.get("deviceId"),UUID.randomUUID().toString(),Boolean.TRUE.equals(old.get("rememberMe")));
        var result=redis.execute(ROTATE,List.of(key(old)),old.get("jti"),next.get("jti"),String.valueOf(JwtUtil.REFRESH_LIFETIME.toSeconds()));
        if(!Long.valueOf(1).equals(result))throw unauthorized();writeCookies(response,next);
    }
    public void logout(String token,HttpServletResponse response) {
        try { var claims=verified(token,"refresh");redis.execute(REVOKE,List.of(key(claims)),claims.get("jti")); }
        catch(ResponseStatusException ignored) { /* 不接受其他 realm；仍清本入口 Cookie。 */ }
        finally { jwt.clearJwtCookie(response,ACCESS_COOKIE);jwt.clearJwtCookie(response,REFRESH_COOKIE); }
    }
    private Customer activeCustomer(String id) {
        var customer=customers.byId(id);if(customer==null || !customer.isEnabled())throw unauthorized();return customer;
    }
    private Map<String,Object> verified(String token,String type) {
        return verified(token,type,"customer");
    }
    private Map<String,Object> verified(String token,String type,String realm) {
        if(!jwt.validate(token,type))throw unauthorized();var claims=jwt.parseToken(token);
        if(!realm.equals(claims.get("realm")) || !(claims.get("id") instanceof String id)
                || !(claims.get("deviceId") instanceof String device) || !(claims.get("jti") instanceof String jti) || jti.isBlank())throw unauthorized();
        try { if(!UUID.fromString(id).toString().equals(id) || !UUID.fromString(device).toString().equals(device))throw unauthorized(); }
        catch(IllegalArgumentException e){throw unauthorized();}return claims;
    }
    private Map<String,Object> claims(Customer customer,String device,String jti,boolean remember) {
        return Map.of("id",customer.getId(),"deviceId",device,"jti",jti,"rememberMe",remember,"realm","customer");
    }
    private String key(Map<String,Object> claims){return ("customer_app".equals(claims.get("realm"))?"shop:customer:mobile:session:":"shop:customer:session:")+claims.get("id")+":"+claims.get("deviceId");}
    private void writeCookies(HttpServletResponse response,Map<String,Object> claims) {
        boolean remember=Boolean.TRUE.equals(claims.get("rememberMe"));
        jwt.setJwtCookie(response,ACCESS_COOKIE,jwt.genAccessToken(claims),remember?(int)JwtUtil.ACCESS_LIFETIME.toSeconds():-1);
        jwt.setJwtCookie(response,REFRESH_COOKIE,jwt.genRefreshToken(claims),remember?(int)JwtUtil.REFRESH_LIFETIME.toSeconds():-1);
    }
    /** 登入失敗一律同一訊息，不揭露帳號是否存在。 */
    private ResponseStatusException badCredentials(){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"帳號或密碼不正確");}
    private ResponseStatusException unauthorized(){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"顧客登入狀態無效");}
}
