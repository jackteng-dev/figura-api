package dev.jackteng.figura.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/**
 * 公開沙盒初始化資源（spec 第 3 節，schemaVersion 2）：{@code <seed>/catalog.json} 與 {@code <seed>/images/}。
 * <p>讀取時就完整驗證：結構、AI 標示、主檔與訂單引用、每個檔案存在且位於 seed 目錄內、大小、類型與 SHA-256。
 * 任何一項不符就丟出例外，<b>在任何資料寫入之前</b>失敗，不會留下半套。</p>
 * 會員的 password 欄位一律忽略（佔位字串）：builtin 會員由 API 自己以私有環境密碼建立，其他會員由 Seeder 執行時隨機產生。
 */
public final class SandboxSeedCatalog {
    public static final String AI_MARKER = "AI 生成概念圖｜展示商品";
    public static final List<String> IMAGE_TYPES = List.of("MAIN", "FACE", "DETAIL", "SCENE", "REAR");
    public static final Set<String> ORDER_STATES = Set.of("PENDING_PAYMENT", "PAID", "SHIPPED", "DELIVERED", "RETURN_REQUESTED");
    static final long MAX_CATALOG_BYTES = 4L * 1024 * 1024, MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    /** 圖片只記錄路徑與預期 SHA；上傳前再讀一次並重新核對（不把全部圖片放在記憶體，也避免驗證後被替換）。 */
    public record Image(String type, int position, String alt, Path root, Path file, String sha256, String contentType) {
        /** 重新核對路徑（不跟隨連結、仍在 seed 目錄內），最多讀取上限＋1 byte，再核對 SHA 與類型；不符丟 {@link Invalid}。 */
        public byte[] read() {
            inside(root, root.relativize(file).toString().replace('\\', '/'));
            byte[] bytes = bounded(file, MAX_IMAGE_BYTES, "圖片");
            if (!SandboxSeedCatalog.sha256(bytes).equals(sha256) || !contentType.equals(sniff(bytes))) throw invalid("圖片在驗證後被變更：" + file.getFileName());
            return bytes;
        }
    }
    public record Preorder(int capacity, int deadlineDays, String deadlineTime, int arrivalFromDays, int arrivalToDays) {}
    public record Item(String code, String title, String brand, String series, String category, String variant, int priceTwd, int stock,
                       Preorder preorder, String description, List<Image> images) {}
    public record Customer(String key, String email, String phone, boolean builtin) {}
    public record OrderLine(String code, int quantity) {}
    public record Order(String customer, List<OrderLine> lines, String state) {}

    private final List<Item> items; private final List<Customer> customers; private final List<Order> orders; private final String fingerprint;
    private SandboxSeedCatalog(List<Item> items, List<Customer> customers, List<Order> orders, String fingerprint) {
        this.items = items; this.customers = customers; this.orders = orders; this.fingerprint = fingerprint;
    }
    /** catalog.json 的 SHA-256（圖片 SHA 已列在其中），作為這份 seed 內容的指紋。 */
    public String fingerprint() { return fingerprint; }
    public List<Item> items() { return items; }
    public List<Customer> customers() { return customers; }
    public List<Order> orders() { return orders; }

    public static final class Invalid extends RuntimeException { Invalid(String m) { super(m); } }
    private static Invalid invalid(String m) { return new Invalid("沙盒初始化資源不正確：" + m); }

    /** 讀取並完整驗證 seed 目錄；失敗丟 {@link Invalid}（訊息不含檔案內容）。 */
    public static SandboxSeedCatalog load(Path dir, ObjectMapper json) {
        Path root;
        try { root = dir.toRealPath(); } catch (IOException e) { throw invalid("找不到 seed 目錄"); }
        if (!Files.isDirectory(root)) throw invalid("seed 路徑不是目錄");
        Path catalogFile = inside(root, "catalog.json");
        JsonNode doc;
        byte[] raw = bounded(catalogFile, MAX_CATALOG_BYTES, "catalog.json");
        try { doc = json.readTree(raw); } catch (IOException e) { throw invalid("無法讀取 catalog.json"); }
        if (doc == null || !doc.isObject()) throw invalid("catalog.json 必須是物件");
        if (doc.path("schemaVersion").asInt(-1) != 2 || !doc.path("schemaVersion").isInt()) throw invalid("schemaVersion 必須是 2（含 images[]）");
        if (!AI_MARKER.equals(doc.path("aiDisclosureMarker").asText(null))) throw invalid("aiDisclosureMarker 不正確");

        var items = new ArrayList<Item>(); var codes = new HashSet<String>(); var titles = new HashSet<String>(); var verified = new HashMap<Path, String>();
        JsonNode itemNodes = doc.path("items");
        if (!itemNodes.isArray() || itemNodes.isEmpty() || itemNodes.size() > 500) throw invalid("items 必須是 1–500 件的陣列");
        for (JsonNode n : itemNodes) {
            String code = text(n, "code", 64);
            if (!code.matches("FIGURA-AI-[0-9]{3}") || !codes.add(code)) throw invalid("商品 code 格式不正確或重複：" + code);
            String title = text(n, "title", 120), description = text(n, "description", 8000);
            if (!description.startsWith(AI_MARKER)) throw invalid(code + " 的 description 必須以 AI 標示開頭");
            if (!titles.add(title)) throw invalid("商品名稱重複：" + title);
            int price = integer(n, "priceTwd", 1, 99_999_999), stock = integer(n, "stock", 0, 1_000_000);
            Preorder preorder = null;
            JsonNode p = n.path("preorder");
            if (!p.isNull()) {
                if (!p.isObject()) throw invalid(code + " 的 preorder 必須是物件或 null");
                String time = text(p, "deadlineTime", 16);
                if (!time.matches("([01][0-9]|2[0-3]):[0-5][0-9]\\+08:00")) throw invalid(code + " 的 deadlineTime 格式不正確");
                preorder = new Preorder(integer(p, "capacity", 1, 100_000), integer(p, "deadlineDaysFromReset", 1, 3650), time,
                        integer(p, "arrivalFromDaysFromReset", 0, 3650), integer(p, "arrivalToDaysFromReset", 0, 3650));
                if (preorder.arrivalToDays() < preorder.arrivalFromDays()) throw invalid(code + " 的預估到貨區間不正確");
                if (preorder.arrivalFromDays() < preorder.deadlineDays()) throw invalid(code + " 的最早到貨日不可早於截止日");
            }
            var images = new ArrayList<Image>();
            JsonNode imageNodes = n.path("images");
            if (!imageNodes.isArray() || imageNodes.size() != IMAGE_TYPES.size()) throw invalid(code + " 必須有 MAIN／FACE／DETAIL／SCENE／REAR 五張圖片");
            for (int i = 0; i < IMAGE_TYPES.size(); i++) {
                JsonNode im = imageNodes.get(i);
                String type = text(im, "type", 16);
                if (!IMAGE_TYPES.get(i).equals(type) || integer(im, "position", 0, 5) != i) throw invalid(code + " 的圖片類型或排序不正確（應為 MAIN、FACE、DETAIL、SCENE、REAR，position 0–4）");
                String rel = text(im, "file", 200), sha = text(im, "sha256", 64), contentType = text(im, "contentType", 32), alt = text(im, "alt", 160);
                if (!rel.matches("images/[A-Za-z0-9][A-Za-z0-9._-]{0,150}") || rel.contains("..")) throw invalid(code + " 的圖片路徑不正確");
                if (!sha.matches("[0-9a-f]{64}")) throw invalid(code + " 的圖片 SHA-256 格式不正確");
                if (!contentType.equals("image/png") && !contentType.equals("image/jpeg")) throw invalid(code + " 的圖片類型只接受 PNG／JPEG");
                Path file = inside(root, rel);
                String actual = verified.get(file);
                if (actual == null) { actual = digest(file, code); verified.put(file, actual); }
                if (!actual.equals(sha + "|" + contentType)) throw invalid(code + " 的圖片 SHA-256 或類型不符：" + rel);
                images.add(new Image(type, i, alt, root, file, sha, contentType));
            }
            String series = text(n, "series", 80);
            if ((title + "｜" + series + "展示預購").length() > 120) throw invalid(code + " 的名稱與系列過長（預購批次名稱最多 120 字）");
            items.add(new Item(code, title, text(n, "brand", 80), series, text(n, "category", 80), text(n, "variant", 80),
                    price, stock, preorder, description, List.copyOf(images)));
        }

        var customers = new ArrayList<Customer>(); var keys = new HashSet<String>(); var emails = new HashSet<String>(); var phones = new HashSet<String>();
        JsonNode customerNodes = doc.path("customers");
        if (!customerNodes.isArray() || customerNodes.size() > 50) throw invalid("customers 必須是陣列（最多 50）");
        for (JsonNode c : customerNodes) {
            String key = text(c, "key", 64), email = text(c, "email", 254).toLowerCase(Locale.ROOT);
            boolean builtin = c.path("builtin").asBoolean(false);
            if (!key.matches("[a-z0-9_]{1,64}") || !keys.add(key)) throw invalid("會員 key 不正確或重複：" + key);
            if (!email.matches("[^@\\s]+@example\\.(com|test)") || !emails.add(email)) throw invalid("會員 email 必須是不重複的 example.com／example.test 虛構信箱");
            String phone = null;
            if (!builtin) { phone = text(c, "phone", 16); if (!phone.matches("09[0-9]{8}") || !phones.add(phone)) throw invalid("會員手機格式不正確或重複：" + key); }
            customers.add(new Customer(key, email, phone, builtin));
        }

        var orders = new ArrayList<Order>();
        var byCode = new HashMap<String, Item>(); items.forEach(i -> byCode.put(i.code(), i));
        JsonNode orderNodes = doc.path("orders");
        if (!orderNodes.isArray() || orderNodes.size() > 200) throw invalid("orders 必須是陣列（最多 200）");
        for (JsonNode o : orderNodes) {
            String customer = text(o, "customer", 64), state = text(o, "state", 32);
            if (!keys.contains(customer)) throw invalid("訂單引用不存在的會員：" + customer);
            if (!ORDER_STATES.contains(state)) throw invalid("訂單狀態不支援：" + state);
            var lines = new ArrayList<OrderLine>(); var seen = new HashSet<String>();
            JsonNode lineNodes = o.path("items");
            if (!lineNodes.isArray() || lineNodes.isEmpty() || lineNodes.size() > 20) throw invalid("訂單品項必須是 1–20 項");
            for (JsonNode l : lineNodes) {
                if (!l.isArray() || l.size() != 2 || !l.get(0).isTextual() || !l.get(1).isInt()) throw invalid("訂單品項格式必須是 [code, quantity]");
                String code = l.get(0).asText(); int qty = l.get(1).asInt();
                Item item = byCode.get(code);
                if (item == null || !seen.add(code)) throw invalid("訂單引用不存在或重複的商品：" + code);
                if (item.preorder() != null || item.stock() <= 0) throw invalid("訂單只能引用現貨商品：" + code);
                if (qty < 1 || qty > 99) throw invalid("訂單數量不正確：" + code);
                lines.add(new OrderLine(code, qty));
            }
            orders.add(new Order(customer, List.copyOf(lines), state));
        }
        // 現貨數量必須足夠所有訂單（PENDING_PAYMENT 也會保留庫存）
        var demand = new HashMap<String, Integer>();
        for (var o : orders) for (var l : o.lines()) demand.merge(l.code(), l.quantity(), Integer::sum);
        demand.forEach((code, qty) -> { if (byCode.get(code).stock() < qty) throw invalid("訂單需求超過現貨數量：" + code); });
        return new SandboxSeedCatalog(List.copyOf(items), List.copyOf(customers), List.copyOf(orders), sha256(raw));
    }

    /** 解析 seed 目錄內的相對路徑：不得跳出目錄、不得是符號連結或非一般檔案。 */
    private static Path inside(Path root, String rel) {
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root)) throw invalid("檔案路徑超出 seed 目錄");
        try {
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) throw invalid("找不到檔案：" + rel);
            if (!p.toRealPath().startsWith(root)) throw invalid("檔案路徑超出 seed 目錄");
        } catch (IOException e) { throw invalid("找不到檔案：" + rel); }
        return p;
    }
    /** 串流計算 SHA-256 並辨識檔頭；回傳 "sha|contentType"。 */
    private static String digest(Path file, String code) {
        try {
            long size = Files.size(file);
            if (size <= 0 || size > MAX_IMAGE_BYTES) throw invalid(code + " 的圖片大小不正確（最大 5MB）");
            var md = MessageDigest.getInstance("SHA-256");
            byte[] head = null, buf = new byte[65536];
            long total = 0;
            try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_IMAGE_BYTES) throw invalid(code + " 的圖片大小不正確（最大 5MB）");
                    if (head == null) head = Arrays.copyOf(buf, Math.min(n, 16));
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest()) + "|" + sniff(head == null ? new byte[0] : head);
        } catch (IOException e) { throw invalid(code + " 的圖片無法讀取"); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    /** 實際最多讀取 max＋1 byte（不依賴先前的大小檢查），超過或為空即失敗；不跟隨符號連結。 */
    private static byte[] bounded(Path file, long max, String what) {
        try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes((int) max + 1);
            if (bytes.length == 0 || bytes.length > max) throw invalid(what + " 大小不正確：" + file.getFileName());
            return bytes;
        } catch (IOException e) { throw invalid(what + " 無法讀取：" + file.getFileName()); }
    }
    private static String sniff(byte[] b) {
        if (b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length > 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) return "image/jpeg";
        return "unknown";
    }
    static String sha256(byte[] b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String text(JsonNode n, String field, int max) {
        JsonNode v = n.path(field);
        if (!v.isTextual() || v.asText().isBlank() || v.asText().length() > max) throw invalid("欄位 " + field + " 缺少或過長");
        return v.asText().strip();
    }
    private static int integer(JsonNode n, String field, int min, int max) {
        JsonNode v = n.path(field);
        if (!v.isInt() || v.asInt() < min || v.asInt() > max) throw invalid("欄位 " + field + " 必須是 " + min + "–" + max + " 的整數");
        return v.asInt();
    }
}
