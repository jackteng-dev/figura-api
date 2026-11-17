package dev.jackteng.figura.controller;

import dev.jackteng.figura.dto.CustomerPayloads;
import dev.jackteng.figura.pojo.CustomerPrincipal;
import dev.jackteng.figura.pojo.Result;
import dev.jackteng.figura.service.CustomerEmailService;
import dev.jackteng.figura.service.CustomerSessionService;
import dev.jackteng.figura.utils.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController

public class CustomerController {
    private final CustomerSessionService sessions;
    private final CustomerEmailService emails;
    public CustomerController(CustomerSessionService sessions,CustomerEmailService emails){this.sessions=sessions;this.emails=emails;}
    @PostMapping("/customer/auth/register") @ResponseStatus(HttpStatus.CREATED)
    public Result<CustomerPayloads.View> register(@RequestBody @Valid CustomerPayloads.Registration input){
        var customer=sessions.register(input);
        emails.sendVerification(customer); // 新版註冊（有 Email）寄出驗證信；舊版無 Email 時不動作
        return Result.success(CustomerPayloads.View.from(customer));
    }
    @PostMapping("/customer/auth/email/verify")
    public Result<CustomerPayloads.View> verifyEmail(@RequestBody @Valid CustomerPayloads.VerifyEmail input){return Result.success(emails.verify(input.token()));}
    @PostMapping("/customer/auth/password/forgot")
    public Result<Void> forgot(@RequestBody @Valid CustomerPayloads.ForgotPassword input){emails.forgot(input.email());return Result.success();}
    @PostMapping("/customer/auth/password/reset")
    public Result<Void> reset(@RequestBody @Valid CustomerPayloads.ResetPassword input){emails.reset(input.token(),input.password());return Result.success();}
    @PostMapping("/customer/email/verification")
    public Result<Void> resendVerification(@AuthenticationPrincipal CustomerPrincipal current){emails.resendVerification(current.id());return Result.success();}
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
