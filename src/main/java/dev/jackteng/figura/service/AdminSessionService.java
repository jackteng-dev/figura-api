package dev.jackteng.figura.service;

import dev.jackteng.figura.pojo.Permission;
import dev.jackteng.figura.pojo.User;
import dev.jackteng.figura.utils.JwtUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminSessionService {
    private final AdminUserService users;
    private final RoleService roles;
    private final BCryptPasswordEncoder passwords;
    private final StringRedisTemplate redis;
    private final JwtUtil jwt;
    private static final DefaultRedisScript<Long> ROTATE = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1])==ARGV[1] then redis.call('SET',KEYS[1],ARGV[2],'EX',ARGV[3]); return 1 end; return 0", Long.class);
    private static final DefaultRedisScript<Long> REVOKE = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end; return 0", Long.class);
    public AdminSessionService(AdminUserService users, RoleService roles, BCryptPasswordEncoder passwords, StringRedisTemplate redis, JwtUtil jwt) {
        this.users = users; this.roles = roles; this.passwords = passwords; this.redis = redis; this.jwt = jwt;
    }
    public User login(String username, String password, String device, boolean remember, HttpServletResponse response) {
        checkDevice(device);
        if (username == null || username.length() > 64 || password == null || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) throw unauthorized();
        User user = users.findByUserName(username);
        if (user == null || !"F".equals(user.getIsStop()) || !passwords.matches(password, user.getPassword())) throw unauthorized();
        user.setPermissions(permissions(user.getId()));
        if (user.getPermissions().isEmpty()) throw unauthorized();
        var claims = claims(user, device, UUID.randomUUID().toString(), remember);
        users.updateLoginTime(user.getId());
        redis.opsForValue().set(key(claims), (String) claims.get("jti"), JwtUtil.REFRESH_LIFETIME);
        writeCookies(response, claims); return user;
    }
    public void refresh(String token, HttpServletResponse response) {
        var old = verified(token, "refresh"); User user = activeUser(old);
        if (permissions(user.getId()).isEmpty()) throw unauthorized();
        var next = claims(user, (String) old.get("deviceId"), UUID.randomUUID().toString(), Boolean.TRUE.equals(old.get("rememberMe")));
        Long result = redis.execute(ROTATE, List.of(key(old)), old.get("jti"), next.get("jti"), String.valueOf(JwtUtil.REFRESH_LIFETIME.toSeconds()));
        if (!Long.valueOf(1).equals(result)) throw unauthorized();
        writeCookies(response, next);
    }
    public User authenticate(String token) {
        var claims = verified(token, "access");
        if (!claims.get("jti").equals(redis.opsForValue().get(key(claims)))) throw unauthorized();
        User user = activeUser(claims); user.setPermissions(permissions(user.getId()));
        if (user.getPermissions().isEmpty()) throw unauthorized(); return user;
    }
    public void logout(String token, HttpServletResponse response) {
        try { var claims = verified(token, "refresh"); redis.execute(REVOKE, List.of(key(claims)), claims.get("jti")); }
        catch (ResponseStatusException ignored) { /* 無效 Cookie 仍必須清除。 */ }
        finally { jwt.clearJwtCookie(response, "accessToken"); jwt.clearJwtCookie(response, "refreshToken"); }
    }
    private User activeUser(Map<String, Object> claims) {
        User user = users.findByUserName((String) claims.get("username"));
        if (user == null || !"F".equals(user.getIsStop()) || !String.valueOf(user.getId()).equals(String.valueOf(claims.get("id")))
                || !(claims.get("authVersion") instanceof Number epoch) || epoch.intValue()!=user.getAuthVersion()) throw unauthorized();
        return user;
    }
    private List<Permission> permissions(Integer id) { var list = roles.getUserBackendPermission(id); return list == null ? List.of() : list; }
    private Map<String, Object> verified(String token, String type) {
        if (!jwt.validate(token, type)) throw unauthorized(); var claims = jwt.parseToken(token);
        if (!"staff".equals(claims.get("realm")) || !(claims.get("username") instanceof String) || !(claims.get("jti") instanceof String jti) || jti.isBlank()
                || !(claims.get("deviceId") instanceof String device)) throw unauthorized();
        checkDevice(device); return claims;
    }
    private void checkDevice(String device) {
        try { if (device == null || !UUID.fromString(device).toString().equals(device)) throw unauthorized(); }
        catch (IllegalArgumentException e) { throw unauthorized(); }
    }
    private String key(Map<String, Object> claims) { return "shop:session:" + claims.get("id") + ":" + claims.get("deviceId"); }
    private Map<String, Object> claims(User user, String device, String jti, boolean remember) {
        return Map.of("id", user.getId(), "username", user.getUsername(), "deviceId", device, "jti", jti, "rememberMe", remember, "realm", "staff", "authVersion", user.getAuthVersion());
    }
    private void writeCookies(HttpServletResponse response, Map<String, Object> claims) {
        boolean remember = Boolean.TRUE.equals(claims.get("rememberMe"));
        jwt.setJwtCookie(response, "accessToken", jwt.genAccessToken(claims), remember ? (int) JwtUtil.ACCESS_LIFETIME.toSeconds() : -1);
        jwt.setJwtCookie(response, "refreshToken", jwt.genRefreshToken(claims), remember ? (int) JwtUtil.REFRESH_LIFETIME.toSeconds() : -1);
    }
    private ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登入狀態無效或帳號無法使用"); }
}
