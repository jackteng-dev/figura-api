package dev.jackteng.figura.controller;

import dev.jackteng.figura.dto.CustomerPayloads;
import dev.jackteng.figura.dto.MobileAuthPayloads.*;
import dev.jackteng.figura.pojo.Result;
import dev.jackteng.figura.service.CustomerSessionService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/mobile/customer/auth")
public class MobileAuthController {
    private final CustomerSessionService sessions;
    private final dev.jackteng.figura.service.CustomerEmailService emails;
    public MobileAuthController(CustomerSessionService sessions,dev.jackteng.figura.service.CustomerEmailService emails){this.sessions=sessions;this.emails=emails;}
    private void noStore(HttpServletResponse response){response.setHeader("Cache-Control","no-store");response.setHeader("Pragma","no-cache");}
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public Result<CustomerPayloads.View> register(@RequestBody @Valid CustomerPayloads.Registration in,HttpServletResponse response){noStore(response);var customer=sessions.register(in);emails.sendVerification(customer);return Result.success(CustomerPayloads.View.from(customer));}
    @PostMapping("/login")
    public Result<Tokens> login(@RequestBody @Valid CustomerPayloads.Login in,HttpServletResponse response){noStore(response);return Result.success(sessions.mobileLogin(in));}
    @PostMapping("/refresh")
    public Result<Tokens> refresh(@RequestBody @Valid Refresh in,HttpServletResponse response){noStore(response);return Result.success(sessions.mobileRefresh(in.refreshToken()));}
    @PostMapping("/logout")
    public Result<Void> logout(@RequestBody @Valid Refresh in,HttpServletResponse response){noStore(response);sessions.mobileLogout(in.refreshToken());return Result.success();}
}
