package dev.jackteng.figura.controller;

import dev.jackteng.figura.dto.CatalogPayloads.Page;
import dev.jackteng.figura.dto.EngagementPayloads.*;
import dev.jackteng.figura.pojo.CustomerPrincipal;
import dev.jackteng.figura.pojo.Result;
import dev.jackteng.figura.service.EngagementService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Commerce V2 Phase 3：收藏、商品提醒、通知偏好、站內通知（Web 用 Cookie＋CSRF，App 用 /mobile Bearer）。 */
@RestController @RequestMapping({"/customer", "/mobile/customer"})
public class EngagementController {
    private final EngagementService service;
    public EngagementController(EngagementService service) { this.service = service; }

    @GetMapping("/favorites") public Result<Page<Favorite>> favorites(@AuthenticationPrincipal CustomerPrincipal c, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) { return Result.success(service.favorites(c.id(), page, pageSize)); }
    @GetMapping("/favorites/ids") public Result<FavoriteIds> favoriteIds(@AuthenticationPrincipal CustomerPrincipal c) { return Result.success(service.favoriteIds(c.id())); }
    @PutMapping("/favorites/{productId}") public Result<FavoriteIds> favorite(@AuthenticationPrincipal CustomerPrincipal c, @PathVariable String productId) { return Result.success(service.favorite(c.id(), productId)); }
    @DeleteMapping("/favorites/{productId}") public Result<FavoriteIds> unfavorite(@AuthenticationPrincipal CustomerPrincipal c, @PathVariable String productId) { return Result.success(service.unfavorite(c.id(), productId)); }
    @PostMapping("/favorites/merge") public Result<MergeResult> merge(@AuthenticationPrincipal CustomerPrincipal c, @RequestBody @Valid Merge in) { return Result.success(service.merge(c.id(), in)); }

    @GetMapping("/product-alerts") public Result<List<ProductAlert>> alerts(@AuthenticationPrincipal CustomerPrincipal c) { return Result.success(service.alerts(c.id())); }
    @PutMapping("/product-alerts/{productId}") public Result<List<String>> setAlerts(@AuthenticationPrincipal CustomerPrincipal c, @PathVariable String productId, @RequestBody @Valid AlertSet in) { return Result.success(service.setAlerts(c.id(), productId, in)); }

    @GetMapping("/notification-preferences") public Result<Preferences> preferences(@AuthenticationPrincipal CustomerPrincipal c) { return Result.success(service.preferences(c.id())); }
    @PutMapping("/notification-preferences") public Result<Preferences> savePreferences(@AuthenticationPrincipal CustomerPrincipal c, @RequestBody @Valid PreferencesWrite in) { return Result.success(service.savePreferences(c.id(), in)); }

    @GetMapping("/notifications") public Result<Inbox> inbox(@AuthenticationPrincipal CustomerPrincipal c, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize, @RequestParam(defaultValue = "false") boolean unread) { return Result.success(service.inbox(c.id(), page, pageSize, unread)); }
    @PostMapping("/notifications/read") public Result<ReadResult> read(@AuthenticationPrincipal CustomerPrincipal c, @RequestBody @Valid MarkRead in) { return Result.success(service.markRead(c.id(), in)); }
}
