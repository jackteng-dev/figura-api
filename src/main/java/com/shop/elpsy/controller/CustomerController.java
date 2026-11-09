package com.shop.elpsy.controller;

import com.shop.elpsy.dto.CustomerPayloads;
import com.shop.elpsy.pojo.CustomerPrincipal;
import com.shop.elpsy.pojo.Result;
import com.shop.elpsy.service.CustomerSessionService;
import com.shop.elpsy.utils.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController

public class CustomerController {
    private final CustomerSessionService sessions;
    public CustomerController(CustomerSessionService sessions){this.sessions=sessions;}
    @PostMapping("/customer/auth/register") @ResponseStatus(HttpStatus.CREATED)
    public Result<CustomerPayloads.View> register(@RequestBody @Valid CustomerPayloads.Registration input){return Result.success(sessions.register(input));}
    @PostMapping("/customer/auth/login")
    public Result<CustomerPayloads.View> login(@RequestBody @Valid CustomerPayloads.Login input,HttpServletResponse response){return Result.success(sessions.login(input,response));}
    @PostMapping("/customer/auth/refresh")
    public Result<Void> refresh(HttpServletRequest request,HttpServletResponse response){sessions.refresh(JwtUtil.extractCookieToken(request,CustomerSessionService.REFRESH_COOKIE),response);return Result.success();}
    @PostMapping("/customer/auth/logout")
    public Result<Void> logout(HttpServletRequest request,HttpServletResponse response){sessions.logout(JwtUtil.extractCookieToken(request,CustomerSessionService.REFRESH_COOKIE),response);return Result.success();}
    @GetMapping({"/customer/me","/mobile/customer/me"})
    public Result<CustomerPayloads.View> me(@AuthenticationPrincipal CustomerPrincipal current){return Result.success(sessions.ownProfile(current.id()));}
    @PatchMapping({"/customer/me","/mobile/customer/me"})
    public Result<CustomerPayloads.View> update(@AuthenticationPrincipal CustomerPrincipal current,@RequestBody @Valid CustomerPayloads.Profile profile){return Result.success(sessions.updateOwnProfile(current.id(),profile));}
}
