package com.shop.elpsy.controller;

import com.shop.elpsy.dto.CustomerPayloads;
import com.shop.elpsy.dto.MobileAuthPayloads.*;
import com.shop.elpsy.pojo.Result;
import com.shop.elpsy.service.CustomerSessionService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/mobile/customer/auth")
public class MobileAuthController {
    private final CustomerSessionService sessions;
    public MobileAuthController(CustomerSessionService sessions){this.sessions=sessions;}
    private void noStore(HttpServletResponse response){response.setHeader("Cache-Control","no-store");response.setHeader("Pragma","no-cache");}
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public Result<CustomerPayloads.View> register(@RequestBody @Valid CustomerPayloads.Registration in,HttpServletResponse response){noStore(response);return Result.success(sessions.register(in));}
    @PostMapping("/login")
    public Result<Tokens> login(@RequestBody @Valid CustomerPayloads.Login in,HttpServletResponse response){noStore(response);return Result.success(sessions.mobileLogin(in));}
    @PostMapping("/refresh")
    public Result<Tokens> refresh(@RequestBody @Valid Refresh in,HttpServletResponse response){noStore(response);return Result.success(sessions.mobileRefresh(in.refreshToken()));}
    @PostMapping("/logout")
    public Result<Void> logout(@RequestBody @Valid Refresh in,HttpServletResponse response){noStore(response);sessions.mobileLogout(in.refreshToken());return Result.success();}
}
