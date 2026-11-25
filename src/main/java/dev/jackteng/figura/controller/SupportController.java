package dev.jackteng.figura.controller;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import dev.jackteng.figura.pojo.CustomUserDetails;
import dev.jackteng.figura.pojo.CustomerPrincipal;
import dev.jackteng.figura.pojo.Result;
import dev.jackteng.figura.service.SitePageService;
import dev.jackteng.figura.service.SupportService;
import dev.jackteng.figura.service.TrackingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Commerce V2 Phase 6：政策／說明頁、客服單、出貨追蹤（契約見 docs/SUPPORT_API.md）。 */
@RestController
public class SupportController {
    static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    public record NewTicket(@NotBlank @Pattern(regexp = UUID) String requestId, @NotBlank String category, @NotBlank @Size(max = 120) String subject,
                            @NotBlank @Size(max = 4000) String body, @Pattern(regexp = UUID) String orderId, @Pattern(regexp = UUID) String preorderOrderId) {
        @JsonAnySetter public void unknown(String k, Object v) { throw new IllegalArgumentException("不支援的欄位"); }
    }
    public record Reply(@NotBlank @Pattern(regexp = UUID) String requestId, @NotBlank @Size(max = 4000) String body, Boolean internal) {
        @JsonAnySetter public void unknown(String k, Object v) { throw new IllegalArgumentException("不支援的欄位"); }
    }
    public record Change(@NotBlank @Pattern(regexp = UUID) String requestId, @NotNull @Min(1) Integer expectedVersion, String status, Integer assigneeId, @NotBlank @Size(max = 480) String note) {
        @JsonAnySetter public void unknown(String k, Object v) { throw new IllegalArgumentException("不支援的欄位"); }
    }
    public record PageEdit(@NotBlank @Pattern(regexp = UUID) String requestId, @NotNull @Min(1) Integer expectedVersion, @NotBlank @Size(max = 120) String title,
                           @NotNull @Size(max = 60000) String body, @NotBlank @Size(max = 480) String note) {
        @JsonAnySetter public void unknown(String k, Object v) { throw new IllegalArgumentException("不支援的欄位"); }
    }
    public record PageStep(@NotBlank @Pattern(regexp = UUID) String requestId, @NotNull @Min(1) Integer expectedVersion, @NotBlank @Size(max = 480) String note) {
        @JsonAnySetter public void unknown(String k, Object v) { throw new IllegalArgumentException("不支援的欄位"); }
    }
    public record Track(@NotBlank @Pattern(regexp = UUID) String requestId, @NotBlank String code, @Size(max = 200) String description) {
        @JsonAnySetter public void unknown(String k, Object v) { throw new IllegalArgumentException("不支援的欄位"); }
    }

    private final SupportService support; private final SitePageService pages; private final TrackingService tracking;
    public SupportController(SupportService support, SitePageService pages, TrackingService tracking) { this.support = support; this.pages = pages; this.tracking = tracking; }

    // 公開頁面（未發布者 published=false，不回草稿）
    @GetMapping("/site-pages") public Result<List<SitePageService.PublicPage>> publicPages() { return Result.success(pages.publicList()); }
    @GetMapping("/site-pages/{slug}") public Result<SitePageService.PublicPage> publicPage(@PathVariable String slug) { return Result.success(pages.publicPage(slug)); }

    // 顧客客服單
    @GetMapping({"/customer/support-tickets", "/mobile/customer/support-tickets"})
    public Result<SupportService.Page> mine(@AuthenticationPrincipal CustomerPrincipal c, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) { return Result.success(support.mine(c.id(), page, pageSize)); }
    @PostMapping({"/customer/support-tickets", "/mobile/customer/support-tickets"})
    public Result<SupportService.Applied> create(@AuthenticationPrincipal CustomerPrincipal c, @RequestBody @Valid NewTicket in) {
        return Result.success(support.create(c.id(), in.requestId(), in.category(), in.subject(), in.body(), in.orderId(), in.preorderOrderId()));
    }
    @GetMapping({"/customer/support-tickets/{id}", "/mobile/customer/support-tickets/{id}"})
    public Result<SupportService.Detail> mineDetail(@AuthenticationPrincipal CustomerPrincipal c, @PathVariable String id) { return Result.success(support.mineDetail(c.id(), id)); }
    @PostMapping({"/customer/support-tickets/{id}/messages", "/mobile/customer/support-tickets/{id}/messages"})
    public Result<SupportService.Applied> reply(@AuthenticationPrincipal CustomerPrincipal c, @PathVariable String id, @RequestBody @Valid Reply in) {
        if (Boolean.TRUE.equals(in.internal())) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "不支援的欄位");
        return Result.success(support.customerReply(c.id(), id, in.requestId(), in.body()));
    }
    @GetMapping({"/customer/shipments/{id}/tracking", "/mobile/customer/shipments/{id}/tracking"})
    public Result<TrackingService.Timeline> myTracking(@AuthenticationPrincipal CustomerPrincipal c, @PathVariable String id) { return Result.success(tracking.forCustomer(c.id(), id)); }

    // 員工
    @GetMapping("/admin/support-tickets") public Result<SupportService.Page> queue(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
                                                                                @RequestParam(required = false) String status, @RequestParam(required = false) Integer assigneeId) { return Result.success(support.queue(page, pageSize, status, assigneeId)); }
    @GetMapping("/admin/support-tickets/{id}") public Result<SupportService.Detail> staffDetail(@PathVariable String id) { return Result.success(support.staffDetail(id)); }
    @PostMapping("/admin/support-tickets/{id}/messages") public Result<SupportService.Applied> staffReply(@PathVariable String id, @RequestBody @Valid Reply in, @AuthenticationPrincipal CustomUserDetails actor) {
        return Result.success(support.staffReply(id, in.requestId(), in.body(), Boolean.TRUE.equals(in.internal()), actor));
    }
    @PostMapping("/admin/support-tickets/{id}/status") public Result<SupportService.Applied> status(@PathVariable String id, @RequestBody @Valid Change in, @AuthenticationPrincipal CustomUserDetails actor) {
        return Result.success(support.change(id, "STATUS", in.requestId(), in.expectedVersion(), in.status(), null, in.note(), actor));
    }
    @PostMapping("/admin/support-tickets/{id}/assign") public Result<SupportService.Applied> assign(@PathVariable String id, @RequestBody @Valid Change in, @AuthenticationPrincipal CustomUserDetails actor) {
        return Result.success(support.change(id, "ASSIGN", in.requestId(), in.expectedVersion(), null, in.assigneeId(), in.note(), actor));
    }
    @GetMapping("/admin/site-pages") public Result<List<SitePageService.AdminPage>> adminPages() { return Result.success(pages.list()); }
    @GetMapping("/admin/site-pages/{slug}") public Result<SitePageService.Detail> adminPage(@PathVariable String slug) { return Result.success(pages.detail(slug)); }
    @PutMapping("/admin/site-pages/{slug}") public Result<SitePageService.Applied> edit(@PathVariable String slug, @RequestBody @Valid PageEdit in, @AuthenticationPrincipal CustomUserDetails actor) {
        return Result.success(pages.edit(slug, in.requestId(), in.expectedVersion(), in.title(), in.body(), in.note(), actor));
    }
    @PostMapping("/admin/site-pages/{slug}/{step}") public Result<SitePageService.Applied> step(@PathVariable String slug, @PathVariable String step, @RequestBody @Valid PageStep in, @AuthenticationPrincipal CustomUserDetails actor) {
        String action = switch (step) { case "submit" -> "SUBMIT"; case "publish" -> "PUBLISH"; case "reject" -> "REJECT"; default -> throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND); };
        return Result.success(pages.transition(slug, action, in.requestId(), in.expectedVersion(), in.note(), actor));
    }
    @GetMapping("/admin/shipments/{id}/tracking") public Result<TrackingService.Timeline> staffTracking(@PathVariable String id) { return Result.success(tracking.forStaff(id)); }
    @PostMapping("/admin/shipments/{id}/tracking") public Result<TrackingService.Timeline> track(@PathVariable String id, @RequestBody @Valid Track in, @AuthenticationPrincipal CustomUserDetails actor) {
        return Result.success(tracking.add(id, in.requestId(), in.code(), in.description(), actor));
    }
}
