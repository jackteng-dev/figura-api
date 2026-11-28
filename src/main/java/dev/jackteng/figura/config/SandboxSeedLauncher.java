package dev.jackteng.figura.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 沙盒初始化的程序層預檢：在 Spring 啟動（Flyway、其他 initializer、排程、HTTP）<b>之前</b>，
 * 先確認沙盒總開關與 seed 資源；不通過就直接結束，不會碰資料庫。通過後才以只綁 loopback、隨機埠的方式啟動，
 * 由 {@link SandboxSeeder} 完成 DB 名稱、profile 與鎖的核對後寫入。
 */
public final class SandboxSeedLauncher {
    private SandboxSeedLauncher() {}
    static final String PREFIX = "--" + SandboxSeeder.OPTION + "=";

    public static boolean requested(String[] args) { return Arrays.stream(args).anyMatch(a -> a.equals("--" + SandboxSeeder.OPTION) || a.startsWith(PREFIX)); }

    /** 回傳 null 表示可以繼續啟動；否則是應直接結束的 exit code（與 {@link SandboxSeeder} 相同：2 參數、3 拒絕、4 資源）。 */
    public static Integer preflight(String[] args, UnaryOperator<String> setting) {
        List<String> dirs = Arrays.stream(args).filter(a -> a.startsWith(PREFIX)).map(a -> a.substring(PREFIX.length())).toList();
        if (dirs.size() != 1 || dirs.get(0).isBlank() || Arrays.stream(args).filter(a -> a.equals("--" + SandboxSeeder.OPTION)).count() > 0) {
            System.err.println("沙盒初始化：--" + SandboxSeeder.OPTION + "=<目錄> 必須且只能指定一次"); return 2;
        }
        if (!"true".equals(setting.apply("SHOP_SANDBOX_MODE"))) {
            System.err.println("沙盒初始化拒絕執行：只有 SHOP_SANDBOX_MODE=true 的公開沙盒可以執行初始化（未連線資料庫）"); return 3;
        }
        try { SandboxSeedCatalog.load(Path.of(dirs.get(0)), new ObjectMapper()); }
        catch (SandboxSeedCatalog.Invalid e) { System.err.println(e.getMessage() + "（未連線資料庫，未寫入任何資料）"); return 4; }
        catch (RuntimeException e) { System.err.println("沙盒初始化資源不正確：無法讀取 seed 目錄（未寫入任何資料）"); return 4; }
        return null;
    }

    /** 初始化程序不對外提供 HTTP：只綁 127.0.0.1 的隨機埠（命令列參數優先於環境設定）。 */
    public static String[] launchArgs(String[] args) {
        // 先移除任何既有的 address／port 選項，避免與強制值合併成多值而綁定失敗
        var out = new ArrayList<>(Arrays.stream(args).filter(a -> !a.startsWith("--server.address") && !a.startsWith("--server.port")).toList());
        out.add("--server.address=127.0.0.1"); out.add("--server.port=0");
        return out.toArray(String[]::new);
    }

    /** 設定值來源與 Spring 相同的兩個最常見位置：JVM 系統屬性優先，其次環境變數。 */
    public static String setting(String name) { String v = System.getProperty(name); return v != null ? v : System.getenv(name); }
}
