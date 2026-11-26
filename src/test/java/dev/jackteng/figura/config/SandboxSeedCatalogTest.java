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
