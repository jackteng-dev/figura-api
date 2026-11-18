package dev.jackteng.figura.controller;

import dev.jackteng.figura.dto.CheckoutPayloads.*;
import dev.jackteng.figura.pojo.CustomerPrincipal;
import dev.jackteng.figura.pojo.Result;
import dev.jackteng.figura.service.CheckoutService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 統一結帳（網站 Cookie＋CSRF、App Bearer）。owner 只取 Principal；價格與分組都由伺服器計算。 */
@RestController @RequestMapping({"/customer/checkout", "/mobile/customer/checkout"})
public class CheckoutController {
    private final CheckoutService service;
    public CheckoutController(CheckoutService service) { this.service = service; }
    @GetMapping("/quote") public Result<Quote> quote(@AuthenticationPrincipal CustomerPrincipal user) { return Result.success(service.quote(user.id())); }
    @PostMapping public Result<Placed> place(@AuthenticationPrincipal CustomerPrincipal user, @RequestBody @Valid Place input) { return Result.success(service.place(user.id(), input)); }
}
