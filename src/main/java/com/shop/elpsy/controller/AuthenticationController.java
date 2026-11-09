package com.shop.elpsy.controller;

import com.shop.elpsy.pojo.CustomUserDetails;
import com.shop.elpsy.pojo.Result;
import com.shop.elpsy.pojo.User;
import com.shop.elpsy.service.AdminSessionService;
import com.shop.elpsy.service.AdminUserService;
import com.shop.elpsy.utils.JwtUtil;
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
    public Result<User> current(@AuthenticationPrincipal CustomUserDetails current) { return Result.success(users.findAllByUserName(current.getUsername())); }
    @PostMapping("/auth/refresh")
    public Result<Void> refresh(HttpServletRequest request, HttpServletResponse response) {
        sessions.refresh(JwtUtil.extractCookieToken(request, "refreshToken"), response); return Result.success();
    }
}
