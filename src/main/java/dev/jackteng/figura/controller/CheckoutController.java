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
    /** 報價含運費：shippingMethod＝HOME／CVS、deliveryStrategy＝SPLIT／COMBINED（省略為宅配、分開寄）。 */
    @GetMapping("/quote") public Result<Quote> quote(@AuthenticationPrincipal CustomerPrincipal user,
            @RequestParam(defaultValue = "HOME") String shippingMethod, @RequestParam(defaultValue = "SPLIT") String deliveryStrategy) {
        if (!shippingMethod.matches("HOME|CVS") || !deliveryStrategy.matches("SPLIT|COMBINED")) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "配送選項不正確");
        return Result.success(service.quote(user.id(), shippingMethod, deliveryStrategy));
    }
    /** 超商取貨的測試門市（test adapter，不是真實門市資料）。 */
    @GetMapping("/pickup-stores") public Result<java.util.List<dev.jackteng.figura.dto.ShippingPayloads.PickupStore>> stores() { return Result.success(dev.jackteng.figura.service.ShippingService.TEST_STORES); }
    @PostMapping public Result<Placed> place(@AuthenticationPrincipal CustomerPrincipal user, @RequestBody @Valid Place input) { return Result.success(service.place(user.id(), input)); }
}
