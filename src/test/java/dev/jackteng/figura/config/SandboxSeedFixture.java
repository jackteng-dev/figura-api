package dev.jackteng.figura.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 測試用 seed 目錄（spec 第 3 節 v2 格式）：95 件虛構商品（現貨 30、預購 64、售完 1）× 5 張小圖，2 位會員、8 筆訂單。
 * 圖片在測試時產生（小尺寸，不觸發衍生尺寸），不含任何正式資料。
 */
public final class SandboxSeedFixture {
    private SandboxSeedFixture() {}
    public static final int PRODUCTS = 95, IN_STOCK = 30, PREORDERS = 64;

    public static Map<String, Object> catalog(Path dir, String run) throws Exception {
        Files.createDirectories(dir.resolve("images"));
        var items = new ArrayList<Map<String, Object>>();
        String[] series = {"測試系列甲", "測試系列乙", "測試系列丙"}, categories = {"PVC 完成品", "可動模型", "GK 雕像", "景品・盒玩"};
        for (int i = 1; i <= PRODUCTS; i++) {
            String code = String.format("FIGURA-AI-%03d", i), title = "沙盒測試公仔 " + i + " " + run;
            var item = new LinkedHashMap<String, Object>();
            item.put("code", code); item.put("title", title); item.put("brand", "沙盒測試品牌" + (i % 2)); item.put("series", series[i % 3]);
            item.put("category", categories[i % 4]); item.put("variant", "標準展示款"); item.put("priceTwd", 500 + i);
            boolean stock = i <= IN_STOCK, preorder = i > IN_STOCK && i <= IN_STOCK + PREORDERS;
            item.put("stock", stock ? 10 : 0);
            item.put("preorder", preorder ? Map.of("capacity", 16, "deadlineDaysFromReset", 12, "deadlineTime", "23:59+08:00", "arrivalFromDaysFromReset", 82, "arrivalToDaysFromReset", 112) : null);
            item.put("description", SandboxSeedCatalog.AI_MARKER + "\n\n「" + title + "」是測試用的虛構展示商品。");
            var images = new ArrayList<Map<String, Object>>();
            for (int t = 0; t < SandboxSeedCatalog.IMAGE_TYPES.size(); t++) {
                String type = SandboxSeedCatalog.IMAGE_TYPES.get(t);
                boolean jpeg = t == 0 || t == 4;   // MAIN 與部分 REAR 是 JPEG，其餘 PNG（與實際圖庫相同）
                byte[] bytes = image(i * 10 + t, jpeg);
                String sha = SandboxSeedCatalog.sha256(bytes), file = "images/" + (t == 0 ? code : sha) + (jpeg ? ".jpg" : ".png");
                Files.write(dir.resolve(file), bytes);
                images.add(Map.of("type", type, "position", t, "alt", title + "，AI 生成示意圖，" + type + "；非實物攝影", "file", file, "sha256", sha,
                        "contentType", jpeg ? "image/jpeg" : "image/png"));
                if (t == 0) { item.put("image", file); item.put("imageAlt", title); item.put("imageSha256", sha); item.put("imageBytes", bytes.length); }
            }
            item.put("images", images);
            items.add(item);
        }
        // 每次執行用不同的虛構信箱與手機，共用測試 DB 中不會互相影響
        String phone = String.format("09%08d", Math.floorMod(run.hashCode(), 50_000_000) * 2);
        String phone2 = String.format("09%08d", Math.floorMod(run.hashCode(), 50_000_000) * 2 + 1);
        var customers = List.of(
                Map.of("key", "sample_buyer_a", "email", "sandbox-seed-a-" + run + "@example.test", "phone", phone, "password", "<placeholder>"),
                Map.of("key", "sample_buyer_b", "email", "sandbox-seed-b-" + run + "@example.test", "phone", phone2, "password", "<placeholder>"));
        var orders = List.of(
                order("sample_buyer_a", "DELIVERED", "FIGURA-AI-001", 1), order("sample_buyer_a", "SHIPPED", "FIGURA-AI-004", 2),
                order("sample_buyer_a", "PAID", "FIGURA-AI-007", 1), order("sample_buyer_a", "PENDING_PAYMENT", "FIGURA-AI-010", 1),
                order("sample_buyer_b", "DELIVERED", "FIGURA-AI-013", 1), order("sample_buyer_b", "PAID", "FIGURA-AI-016", 1),
                order("sample_buyer_b", "RETURN_REQUESTED", "FIGURA-AI-022", 1), order("sample_buyer_b", "SHIPPED", "FIGURA-AI-019", 2));
        var doc = new LinkedHashMap<String, Object>();
        doc.put("schemaVersion", 2); doc.put("aiDisclosureMarker", SandboxSeedCatalog.AI_MARKER);
        doc.put("items", items); doc.put("customers", customers); doc.put("orders", orders);
        return doc;
    }
    private static Map<String, Object> order(String customer, String state, String code, int qty) {
        return Map.of("customer", customer, "state", state, "items", List.of(List.of(code, qty)));
    }
    public static void write(Path dir, Map<String, Object> doc) throws Exception {
        Files.write(dir.resolve("catalog.json"), new ObjectMapper().writeValueAsBytes(doc));
    }
    static byte[] image(int seed, boolean jpeg) throws Exception {
        var img = new BufferedImage(48, 48, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(new Color((seed * 37) % 256, (seed * 91) % 256, (seed * 53) % 256)); g.fillRect(0, 0, 48, 48);
        g.setColor(Color.WHITE); g.fillRect(seed % 40, (seed / 3) % 40, 8, 8); g.dispose();
        var out = new ByteArrayOutputStream(); ImageIO.write(img, jpeg ? "jpeg" : "png", out); return out.toByteArray();
    }
}
