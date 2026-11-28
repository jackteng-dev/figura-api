package dev.jackteng.figura;

import dev.jackteng.figura.config.SandboxSeedLauncher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FiguraApplication {
    public static void main(String[] args) {
        if (SandboxSeedLauncher.requested(args)) {
            // 沙盒初始化：Spring 啟動前先預檢沙盒總開關與 seed 資源，不通過就不連線資料庫
            Integer refused = SandboxSeedLauncher.preflight(args, SandboxSeedLauncher::setting);
            if (refused != null) System.exit(refused);
            args = SandboxSeedLauncher.launchArgs(args);
        }
        SpringApplication.run(FiguraApplication.class, args);
    }
}
