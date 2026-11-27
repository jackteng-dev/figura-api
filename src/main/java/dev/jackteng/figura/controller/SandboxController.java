package dev.jackteng.figura.controller;

import dev.jackteng.figura.config.SandboxMode;
import dev.jackteng.figura.pojo.Result;
import dev.jackteng.figura.service.SandboxDemoService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 公開沙盒端點。正式環境（SHOP_SANDBOX_MODE 未開）一律 404，與不存在的路徑相同；
 * 沒有任何請求參數或 header 可以開啟。
 */
@RestController
public class SandboxController {
    private final SandboxMode mode;
    private final SandboxDemoService demo;
    public SandboxController(SandboxMode mode, SandboxDemoService demo) { this.mode = mode; this.demo = demo; }

    public record Config(boolean quickDemo, boolean mfaDemo, Integer mfaDemoTtlMinutes) {}

    /** 前端只用來決定是否顯示沙盒入口；不含帳號名稱或密碼（由沙盒說明框提供）。 */
    @GetMapping("/sandbox/config")
    public Result<Config> config() {
        if (!mode.enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到資源");
        return Result.success(new Config(mode.quickDemoEnabled(), mode.demoEnabled(), mode.demoEnabled() ? mode.demoTtlMinutes() : null));
    }

    /** 建立 MFA 展示臨時帳號：帳號、密碼只在這次回應出現一次。 */
    @PostMapping("/sandbox/mfa-demo/accounts")
    public Result<SandboxDemoService.Created> createDemoAccount() {
        if (!mode.demoEnabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到資源");
        return Result.success(demo.create());
    }
}
