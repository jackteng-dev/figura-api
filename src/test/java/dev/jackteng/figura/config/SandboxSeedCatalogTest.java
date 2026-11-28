package dev.jackteng.figura.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** seed 資源驗證：缺檔、SHA 不符、路徑跳出、格式錯誤都在任何寫入之前失敗。 */
class SandboxSeedCatalogTest {
    @TempDir Path dir;
    private final ObjectMapper json = new ObjectMapper();

    private Map<String, Object> valid() throws Exception { var doc = SandboxSeedFixture.catalog(dir, "unit"); SandboxSeedFixture.write(dir, doc); return doc; }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> items(Map<String, Object> doc) { return (List<Map<String, Object>>) doc.get("items"); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> images(Map<String, Object> item) { return (List<Map<String, Object>>) item.get("images"); }
    private void rejects(Map<String, Object> doc, String why) throws Exception {
        SandboxSeedFixture.write(dir, doc);
        assertThrows(SandboxSeedCatalog.Invalid.class, () -> SandboxSeedCatalog.load(dir, json), why);
    }

    @Test void acceptsTheFullFormat() throws Exception {
        valid();
        var c = SandboxSeedCatalog.load(dir, json);
        assertEquals(95, c.items().size()); assertEquals(2, c.customers().size()); assertEquals(8, c.orders().size());
        assertEquals(List.of("MAIN", "FACE", "DETAIL", "SCENE", "REAR"), c.items().get(0).images().stream().map(SandboxSeedCatalog.Image::type).toList());
        assertEquals(64, c.items().stream().filter(i -> i.preorder() != null).count());
        assertArrayEquals(Files.readAllBytes(c.items().get(0).images().get(1).file()), c.items().get(0).images().get(1).read());
    }

    @Test void missingOrTamperedFilesFail() throws Exception {
        var doc = valid();
        var first = images(items(doc).get(0));
        Files.delete(dir.resolve((String) first.get(2).get("file")));
        rejects(doc, "缺檔");
        doc = valid();
        Files.write(dir.resolve((String) images(items(doc).get(3)).get(1).get("file")), new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0, 1});
        rejects(doc, "SHA 不符");
        doc = valid();
        var mutable = new LinkedHashMap<>(images(items(doc).get(1)).get(0)); mutable.put("contentType", "image/png");
        var list = new ArrayList<>(images(items(doc).get(1))); list.set(0, mutable); items(doc).get(1).put("images", list);
        rejects(doc, "類型與內容不符");
    }

    @Test void pathsCannotEscapeTheSeedDirectory() throws Exception {
        for (String bad : List.of("../catalog.json", "images/../../x.png", "/etc/passwd", "images/sub/../../../x.png", "catalog.json")) {
            var doc = valid();
            var m = new LinkedHashMap<>(images(items(doc).get(0)).get(1)); m.put("file", bad);
            var list = new ArrayList<>(images(items(doc).get(0))); list.set(1, m); items(doc).get(0).put("images", list);
            rejects(doc, bad);
        }
    }

    @Test void structureRulesAreEnforced() throws Exception {
        var doc = valid(); doc.put("schemaVersion", 1); rejects(doc, "必須 v2");
        doc = valid(); doc.put("aiDisclosureMarker", "x"); rejects(doc, "AI 標示");
        doc = valid(); items(doc).get(0).put("description", "沒有 AI 標示"); rejects(doc, "description 必須以 AI 標示開頭");
        doc = valid(); items(doc).get(1).put("code", "FIGURA-AI-001"); rejects(doc, "code 重複");
        doc = valid(); items(doc).get(0).put("code", "OTHER-001"); rejects(doc, "code 格式");
        doc = valid(); var swapped = new ArrayList<>(images(items(doc).get(0))); Collections.swap(swapped, 1, 2); items(doc).get(0).put("images", swapped); rejects(doc, "類型順序");
        doc = valid(); items(doc).get(0).put("images", images(items(doc).get(0)).subList(0, 4)); rejects(doc, "少一張");
        doc = valid(); doc.put("orders", List.of(Map.of("customer", "sample_buyer_a", "state", "PAID", "items", List.of(List.of("FIGURA-AI-050", 1))))); rejects(doc, "訂單只能用現貨");
        doc = valid(); doc.put("orders", List.of(Map.of("customer", "nobody", "state", "PAID", "items", List.of(List.of("FIGURA-AI-001", 1))))); rejects(doc, "未知會員");
        doc = valid(); doc.put("orders", List.of(Map.of("customer", "sample_buyer_a", "state", "REFUNDED", "items", List.of(List.of("FIGURA-AI-001", 1))))); rejects(doc, "不支援的狀態");
        doc = valid(); doc.put("orders", List.of(Map.of("customer", "sample_buyer_a", "state", "PAID", "items", List.of(List.of("FIGURA-AI-001", 11))))); rejects(doc, "超過現貨");
        doc = valid(); doc.put("customers", List.of(Map.of("key", "x", "email", "real@gmail.com", "phone", "0900000001", "password", "p"))); doc.put("orders", List.of()); rejects(doc, "只接受虛構信箱");
        // 寫入途中才會失敗的條件，在預檢就拒絕
        doc = valid(); items(doc).get(40).put("preorder", Map.of("capacity", 16, "deadlineDaysFromReset", 30, "deadlineTime", "23:59+08:00", "arrivalFromDaysFromReset", 20, "arrivalToDaysFromReset", 40)); rejects(doc, "到貨早於截止");
        doc = valid(); doc.put("customers", List.of(Map.of("key", "a", "email", "a@example.test", "phone", "0900000001", "password", "p"), Map.of("key", "b", "email", "b@example.test", "phone", "0900000001", "password", "p"))); doc.put("orders", List.of()); rejects(doc, "手機重複");
        doc = valid(); items(doc).get(0).put("title", "長".repeat(115)); rejects(doc, "預購批次名稱超過 120 字");
    }

    @Test void readsAreBoundedAndRecheckedAtUploadTime() throws Exception {
        valid();
        var c = SandboxSeedCatalog.load(dir, json);
        var image = c.items().get(2).images().get(1);
        // 驗證後被換成超過上限的檔案：讀取最多上限＋1 byte 即失敗，不配置整個檔案
        Files.write(image.file(), new byte[(int) SandboxSeedCatalog.MAX_IMAGE_BYTES + 10]);
        assertThrows(SandboxSeedCatalog.Invalid.class, image::read);
        // 驗證後被刪除
        var other = c.items().get(3).images().get(2);
        Files.delete(other.file());
        assertThrows(SandboxSeedCatalog.Invalid.class, other::read);
        // 內容指紋：同一份 catalog.json 相同
        valid();
        assertEquals(SandboxSeedCatalog.load(dir, json).fingerprint(), SandboxSeedCatalog.load(dir, json).fingerprint());
    }

    @Test void orderKeysDependOnContentNotPosition() throws Exception {
        valid();
        var c = SandboxSeedCatalog.load(dir, json);
        var ids = Map.of("sample_buyer_a", "A", "sample_buyer_b", "B");
        var keys = SandboxSeeder.orderKeys(c, ids);
        assertEquals(8, new HashSet<>(keys).size());
        var doc = valid();
        @SuppressWarnings("unchecked") var reversed = new ArrayList<>((List<Object>) doc.get("orders")); Collections.reverse(reversed); doc.put("orders", reversed);
        SandboxSeedFixture.write(dir, doc);
        var again = SandboxSeeder.orderKeys(SandboxSeedCatalog.load(dir, json), ids);
        assertEquals(new HashSet<>(keys), new HashSet<>(again), "調整順序不改變每筆訂單的操作編號");
    }

    @Test void launcherRefusesBeforeSpringStarts() throws Exception {
        valid();
        String arg = "--sandbox.seed.dir=" + dir;
        assertEquals(3, SandboxSeedLauncher.preflight(new String[]{arg}, k -> null), "未啟用沙盒");
        assertEquals(3, SandboxSeedLauncher.preflight(new String[]{arg}, k -> "TRUE"), "只接受字面 true");
        assertEquals(2, SandboxSeedLauncher.preflight(new String[]{arg, "--sandbox.seed.dir=/x"}, k -> "true"));
        assertEquals(2, SandboxSeedLauncher.preflight(new String[]{"--sandbox.seed.dir"}, k -> "true"));
        assertEquals(4, SandboxSeedLauncher.preflight(new String[]{"--sandbox.seed.dir=" + dir.resolve("missing")}, k -> "true"));
        assertNull(SandboxSeedLauncher.preflight(new String[]{arg}, k -> "SHOP_SANDBOX_MODE".equals(k) ? "true" : null));
        assertTrue(SandboxSeedLauncher.requested(new String[]{arg})); assertFalse(SandboxSeedLauncher.requested(new String[]{"--server.port=1"}));
        var launch = List.of(SandboxSeedLauncher.launchArgs(new String[]{arg}));
        assertTrue(launch.contains("--server.address=127.0.0.1") && launch.contains("--server.port=0"), "初始化程序不對外提供 HTTP");
    }

    @Test void creationOrderInterleavesSeriesAndReverses() throws Exception {
        valid();
        var c = SandboxSeedCatalog.load(dir, json);
        var order = SandboxSeeder.buildOrder(c.items());
        assertEquals(95, order.size()); assertEquals(95, new HashSet<>(order).size());
        assertNotEquals(order.get(0).series(), order.get(1).series(), "相鄰商品來自不同系列");
        assertEquals(SandboxSeeder.uuid("x"), SandboxSeeder.uuid("x")); assertNotEquals(SandboxSeeder.uuid("x"), SandboxSeeder.uuid("y"));
    }
}
