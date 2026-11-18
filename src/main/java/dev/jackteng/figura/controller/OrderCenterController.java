package dev.jackteng.figura.controller;

import dev.jackteng.figura.dto.OrderCenterPayloads.*;
import dev.jackteng.figura.pojo.CustomerPrincipal;
import dev.jackteng.figura.pojo.Result;
import dev.jackteng.figura.service.OrderCenterService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 統一訂單中心（網站 Cookie 與 App Bearer 共用）。只讀；owner 只取 Principal。 */
@RestController @RequestMapping({"/customer/order-center", "/mobile/customer/order-center"})
public class OrderCenterController {
    private final OrderCenterService service;
    public OrderCenterController(OrderCenterService service) { this.service = service; }

    @GetMapping
    public Result<Page> list(@AuthenticationPrincipal CustomerPrincipal user,
                             @RequestParam(defaultValue = "ALL") String kind, @RequestParam(defaultValue = "ALL") String stage,
                             @RequestParam(defaultValue = "NEWEST") String sort,
                             @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(service.list(user.id(), parse(Kind.class, kind), parse(Stage.class, stage), parse(Sort.class, sort), page, pageSize));
    }
    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "篩選或排序參數不正確"); }
    }
}
