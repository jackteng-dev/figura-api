package dev.jackteng.figura.controller;

import dev.jackteng.figura.pojo.CustomUserDetails;
import dev.jackteng.figura.pojo.Result;
import dev.jackteng.figura.pojo.User;
import dev.jackteng.figura.service.AdminSessionService;
import dev.jackteng.figura.service.AdminUserService;
import dev.jackteng.figura.utils.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class AuthenticationController {
    private final AdminSessionService sessions;
    private final AdminUserService users;
    public AuthenticationController(AdminSessionService sessions, AdminUserService users) { this.sessions = sessions; this.users = users; }
    @GetMapping("/auth/csrf")
    public Result<Map<String, String>> csrf(CsrfToken token) { return Result.success(Map.of("token", token.getToken())); }
    @GetMapping("/auth/me")
    public Result<User> current(@AuthenticationPrincipal CustomUserDetails current) {
        var user = users.findAllByUserName(current.getUsername());
        if (user != null) {
            sessions.withKind(user);   // 帳號類型只供前端顯示
            // 前端據此導向備援碼保存確認；共用展示帳號不能管理 MFA，所以永遠不要求
            user.setMfaBackupAckPending(!"SANDBOX_SHARED".equals(user.getAccountKind()) && mfa.ackPending(current.getId()));
        }
        return Result.success(user);
    }
    @org.springframework.beans.factory.annotation.Autowired private dev.jackteng.figura.service.StaffMfaService mfa;
    @PostMapping("/auth/refresh")
    public Result<Void> refresh(HttpServletRequest request, HttpServletResponse response) {
        sessions.refresh(JwtUtil.extractCookieToken(request, "refreshToken"), response); return Result.success();
    }
}
