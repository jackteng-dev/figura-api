package dev.jackteng.figura.controller;

import dev.jackteng.figura.pojo.Result;
import dev.jackteng.figura.service.CustomerEmailService;
import org.springframework.web.bind.annotation.*;

/**
 * 模擬寄信（信箱驗證／重設密碼）唯讀列表。
 * 路徑放在 /admin/notifications/ 底下，沿用既有「通知詳情」權限（GET /api/admin/notifications/*），不新增權限資料。
 * 字面路徑 mock-emails 優先於 NotificationController 的 /{id}。
 */
@RestController
public class MockEmailController {
    private final CustomerEmailService emails;
    public MockEmailController(CustomerEmailService emails) { this.emails = emails; }
    @GetMapping("/admin/notifications/mock-emails")
    public Result<CustomerEmailService.MockEmailPage> list(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(emails.mockEmails(page, pageSize));
    }
}
