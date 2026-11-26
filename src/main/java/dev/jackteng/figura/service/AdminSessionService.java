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
    private final StaffMfaService mfa;
    /** 舊建構方式（單元測試）：不啟用雙因素。 */
    public AdminSessionService(AdminUserService users, RoleService roles, BCryptPasswordEncoder passwords, StringRedisTemplate redis, JwtUtil jwt) {
        this(users, roles, passwords, redis, jwt, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AdminSessionService(AdminUserService users, RoleService roles, BCryptPasswordEncoder passwords, StringRedisTemplate redis, JwtUtil jwt, StaffMfaService mfa) {
        this.users = users; this.roles = roles; this.passwords = passwords; this.redis = redis; this.jwt = jwt; this.mfa = mfa;
    }
    /** 公開沙盒（預設關閉；只由伺服器環境變數開啟）。單元測試未注入時視為正式環境。 */
    private dev.jackteng.figura.config.SandboxMode sandbox;
    private SandboxDemoService demo;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSandbox(dev.jackteng.figura.config.SandboxMode sandbox, SandboxDemoService demo) { this.sandbox = sandbox; this.demo = demo; }
    private static final String MFA_TOTP = "TOTP", MFA_SANDBOX_SHARED = "SANDBOX_SHARED";
    private boolean sandboxOn() { return sandbox != null && sandbox.enabled(); }
    /** 共用展示帳號：沙盒模式中、帳號名稱與設定「完全相同」。 */
    private boolean shared(String username) { return sandboxOn() && sandbox.isExempt(username); }
    public User login(String username, String password, String device, boolean remember, HttpServletResponse response) {
        checkDevice(device);
        if (username == null || username.length() > 64 || password == null || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) throw unauthorized();
        User user = users.findByUserName(username);
        if (user == null || !"F".equals(user.getIsStop()) || !passwords.matches(password, user.getPassword())) throw unauthorized();
        user.setPermissions(permissions(user.getId()));
        if (user.getPermissions().isEmpty()) throw unauthorized();
        if (sandboxOn()) {
            // 公開沙盒快速展示：共用帳號免 MFA 直接發 session。輸入的帳號必須與設定逐字相同；
            // 大小寫／空白變形一律拒絕（不能讓訪客以變形名稱替共用帳號綁定 MFA 而鎖住其他人）。
            if (sandbox.isExempt(user.getUsername())) {
                if (!user.getUsername().equals(username)) throw unauthorized();
                return withKind(issue(user, device, remember, response, MFA_SANDBOX_SHARED));
            }
            if (demo != null && demo.expired(user.getId())) throw unauthorized();   // 到期的 MFA 展示帳號
        }
        // 強制雙因素：密碼正確只拿到 challenge（已綁定）或綁定 token（未綁定），都不是 session。金鑰未設定時拒絕，不降級。
        if (mfa == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "後台雙因素驗證尚未設定，暫停員工登入，請聯絡系統管理者");
        mfa.requireAvailable();
        var pending = new User();
        if (mfa.enabled(user.getId())) { pending.setMfaRequired(true); pending.setMfaChallenge(mfa.challenge(user.getId(), user.getAuthVersion(), device, remember)); }
        else { pending.setMfaSetupRequired(true); pending.setMfaSetupToken(mfa.setupToken(user.getId(), user.getAuthVersion(), device, remember)); }
        return pending;
    }
    /** 首次綁定完成（驗證碼正確）：發 session，並一次性回傳備援碼。 */
    public User completeEnrollment(String setupToken, String code, HttpServletResponse response) {
        if (mfa == null) throw unauthorized();
        var e = mfa.enroll(setupToken, code);
        User user = sessionUser(e.pending());
        user = withKind(issue(user, e.pending().device(), e.pending().remember(), response, MFA_TOTP));
        user.setBackupCodes(e.backup().codes());
        user.setBackupCodesRequired(e.backup().required());
        user.setBackupCodesGeneratedAt(e.backup().generatedAt());
        user.setBackupBatchId(e.backup().batchId());
        user.setMfaBackupAckPending(e.backup().required());
        return user;
    }
    private User sessionUser(StaffMfaService.Pending p) {
        User user = users.findByUserName(p.username());
        // 驗證通過後才發 session：若期間被重設、停用或權限變更（auth_version 改變），拒絕把舊驗證結果套用到新版本
        if (user == null || user.getId() != p.user() || !"F".equals(user.getIsStop()) || user.getAuthVersion() != p.authVersion() || !mfa.enabled(user.getId())) throw unauthorized();
        if (shared(user.getUsername()) || (sandboxOn() && demo != null && demo.expired(user.getId()))) throw unauthorized();
        user.setPermissions(permissions(user.getId()));
        if (user.getPermissions().isEmpty()) throw unauthorized();
        return user;
    }
    /** 雙因素第二步：challenge＋驗證碼正確後才發 session（帳號狀態與權限重新核對）。 */
    public User completeMfa(String challenge, String code, HttpServletResponse response) {
        if (mfa == null) throw unauthorized();
        var p = mfa.verify(challenge, code);
        return withKind(issue(sessionUser(p), p.device(), p.remember(), response, MFA_TOTP));
    }
    private User issue(User user, String device, boolean remember, HttpServletResponse response, String factor) {
        var claims = claims(user, device, UUID.randomUUID().toString(), remember, factor);
        users.updateLoginTime(user.getId());
        redis.opsForValue().set(key(claims), (String) claims.get("jti"), JwtUtil.REFRESH_LIFETIME);
        writeCookies(response, claims); return user;
    }
    public void refresh(String token, HttpServletResponse response) {
        var old = verified(token, "refresh"); User user = activeUser(old);
        if (permissions(user.getId()).isEmpty()) throw unauthorized();
        var next = claims(user, (String) old.get("deviceId"), UUID.randomUUID().toString(), Boolean.TRUE.equals(old.get("rememberMe")), (String) old.get("mfa"));
        Long result = redis.execute(ROTATE, List.of(key(old)), old.get("jti"), next.get("jti"), String.valueOf(JwtUtil.REFRESH_LIFETIME.toSeconds()));
        if (!Long.valueOf(1).equals(result)) throw unauthorized();
        writeCookies(response, next);
    }
    public User authenticate(String token) {
        var claims = verified(token, "access");
        if (!claims.get("jti").equals(redis.opsForValue().get(key(claims)))) throw unauthorized();
        User user = activeUser(claims); user.setPermissions(permissions(user.getId()));
        if (user.getPermissions().isEmpty()) throw unauthorized();
        withKind(user);
        if (mfa != null) user.setMfaBackupAckPending(!MFA_SANDBOX_SHARED.equals(user.getAccountKind()) && mfa.ackPending(user.getId()));
        return user;
    }
    /** 帳號類型（STAFF／SANDBOX_SHARED／SANDBOX_MFA_DEMO）只供前端顯示；後端授權不依賴這個欄位。 */
    public User withKind(User user) {
        if (!sandboxOn() || demo == null) { user.setAccountKind(dev.jackteng.figura.config.SandboxMode.AccountKind.STAFF.name()); return user; }
        user.setAccountKind(demo.kind(user.getId(), user.getUsername()).name());
        demo.expiresAt(user.getId()).ifPresent(user::setSandboxExpiresAt);
        return user;
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
        // 強制雙因素：沒有 MFA 標記的 session（含部署前發出的）一律無效，需重新登入。
        // 例外只有公開沙盒的共用展示帳號：每次請求都重新核對「目前」沙盒設定，設定移除後 session 立即失效。
        Object factor = claims.get("mfa");
        boolean ok = MFA_TOTP.equals(factor) ? !shared(user.getUsername()) : MFA_SANDBOX_SHARED.equals(factor) && shared(user.getUsername());
        if (!ok) throw unauthorized();
        if (sandboxOn() && demo != null && demo.expired(user.getId())) throw unauthorized();
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
    private Map<String, Object> claims(User user, String device, String jti, boolean remember, String factor) {
        if (!MFA_TOTP.equals(factor) && !MFA_SANDBOX_SHARED.equals(factor)) throw unauthorized();
        return Map.of("id", user.getId(), "username", user.getUsername(), "deviceId", device, "jti", jti, "rememberMe", remember, "realm", "staff", "authVersion", user.getAuthVersion(), "mfa", factor);
    }
    private void writeCookies(HttpServletResponse response, Map<String, Object> claims) {
        boolean remember = Boolean.TRUE.equals(claims.get("rememberMe"));
        jwt.setJwtCookie(response, "accessToken", jwt.genAccessToken(claims), remember ? (int) JwtUtil.ACCESS_LIFETIME.toSeconds() : -1);
        jwt.setJwtCookie(response, "refreshToken", jwt.genRefreshToken(claims), remember ? (int) JwtUtil.REFRESH_LIFETIME.toSeconds() : -1);
    }
    private ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登入狀態無效或帳號無法使用"); }
}
