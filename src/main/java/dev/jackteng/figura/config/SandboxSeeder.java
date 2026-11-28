package dev.jackteng.figura.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.figura.dto.CatalogPayloads;
import dev.jackteng.figura.dto.CustomerPayloads;
import dev.jackteng.figura.dto.GalleryPayloads;
import dev.jackteng.figura.dto.InventoryPayloads;
import dev.jackteng.figura.dto.MasterPayloads;
import dev.jackteng.figura.dto.OrderPayloads;
import dev.jackteng.figura.dto.PaymentPayloads;
import dev.jackteng.figura.dto.PreorderPayloads;
import dev.jackteng.figura.dto.ReturnPayloads;
import dev.jackteng.figura.dto.ShoppingPayloads;
import dev.jackteng.figura.mapper.CustomerMapper;
import dev.jackteng.figura.pojo.CustomUserDetails;
import dev.jackteng.figura.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.*;
import java.util.*;

/**
 * 公開沙盒初始化入口（spec 2.4）。<b>沒有任何 HTTP 端點</b>：只有在 API 程序以命令列參數
 * {@code --sandbox.seed.dir=/seed} 啟動時才執行，執行完程序即結束（exit code 0＝成功，非 0＝失敗）。
 * <ul>
 * <li>只在 {@code SHOP_SANDBOX_MODE=true}、demo profile 且連到隔離 {@code figura_shop_demo} 時執行；否則立即失敗。
 *     參數只讀命令列，不讀環境變數或設定檔，無法透過請求或部署設定觸發。</li>
 * <li>先完整驗證 seed 目錄（{@link SandboxSeedCatalog}：格式、路徑、大小、SHA-256），全部通過才開始寫入。</li>
 * <li>以 MySQL 具名鎖防止同時執行兩次（拿不到鎖立即失敗）。</li>
 * <li>以系統員工 {@code sandbox-seeder}（只有初始化所需權限，執行完即停用）呼叫既有 service，保留原本的驗證、版本、稽核與 AI 標示；
 *     所有操作編號以 seed 內容決定（UUID v3），重跑時已完成的步驟只重播或略過，不重複建立。</li>
 * </ul>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)   // 在 DemoAccountInitializer（員工與示範會員）之後
public class SandboxSeeder implements ApplicationRunner {
    public static final String OPTION = "sandbox.seed.dir", ACTOR = "sandbox-seeder";
    static final String ROLE_NAME = "沙盒初始化", ROLE_DESCRIPTION = "公開沙盒初始化系統帳號（系統建立，執行完即停用）";
    static final List<String> ROLE_PERMISSIONS = List.of("masters.create", "catalog.write.create", "catalog.publish", "catalog.images.upload",
            "inventory.adjust", "preorders.create", "preorders.open", "orders.ship.mock", "orders.deliver.mock");
    private static final String LOCK = "figura_sandbox_seed", MASTER_DESCRIPTION = "Figura AI 原創展示型錄（虛構）";
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Logger log = LoggerFactory.getLogger(SandboxSeeder.class);

    public record Summary(String result, int products, int published, int preorders, int orders, Map<String, Integer> images, String seedSha256) {}

    private final ApplicationContext context; private final SandboxMode sandbox; private final JdbcTemplate db; private final DataSource dataSource;
    private final ObjectMapper json; private final BCryptPasswordEncoder passwords; private final TransactionTemplate tx;
    private final CatalogMasterService masters; private final CatalogService catalog; private final ProductGalleryService gallery;
    private final InventoryService inventory; private final PreorderBatchService batches; private final CustomerRegistrationService registration;
    private final CustomerMapper customers; private final ShoppingService shopping; private final OrderService orders;
    private final PaymentService payments; private final FulfillmentService fulfillment; private final ReturnService returns;
    private final SecureRandom random = new SecureRandom();

    public SandboxSeeder(ApplicationContext context, SandboxMode sandbox, JdbcTemplate db, DataSource dataSource, ObjectMapper json,
                         BCryptPasswordEncoder passwords, PlatformTransactionManager tm, CatalogMasterService masters, CatalogService catalog,
                         ProductGalleryService gallery, InventoryService inventory, PreorderBatchService batches,
                         CustomerRegistrationService registration, CustomerMapper customers, ShoppingService shopping, OrderService orders,
                         PaymentService payments, FulfillmentService fulfillment, ReturnService returns) {
        this.context = context; this.sandbox = sandbox; this.db = db; this.dataSource = dataSource; this.json = json; this.passwords = passwords;
        this.tx = new TransactionTemplate(tm); this.masters = masters; this.catalog = catalog; this.gallery = gallery; this.inventory = inventory;
        this.batches = batches; this.registration = registration; this.customers = customers; this.shopping = shopping; this.orders = orders;
        this.payments = payments; this.fulfillment = fulfillment; this.returns = returns;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) return;   // 一般啟動完全不動作
        int code = execute(args);
        // 一次性程序：完成後關閉 context 並以結果結束（docker compose run --rm）
        System.exit(SpringApplication.exit(context, () -> code));
    }

    /** 執行並回傳 exit code（測試直接呼叫，不結束 JVM）。 */
    public int execute(ApplicationArguments args) {
        var values = args.getOptionValues(OPTION);
        if (values == null || values.size() != 1 || values.get(0).isBlank()) { log.error("沙盒初始化：--{} 必須且只能指定一個目錄", OPTION); return 2; }
        try {
            var summary = seed(Path.of(values.get(0)));
            System.out.println(json.writeValueAsString(summary));   // 摘要只含數量
            return 0;
        } catch (Refused e) {
            log.error("沙盒初始化拒絕執行：{}", e.getMessage()); return 3;
        } catch (SandboxSeedCatalog.Invalid e) {
            log.error("{}（未寫入任何資料）", e.getMessage()); return 4;
        } catch (Exception e) {
            log.error("沙盒初始化失敗：{}；可修正後重跑（已完成的步驟不會重複建立）", e.getMessage(), e); return 1;
        }
    }

    public static final class Refused extends RuntimeException { Refused(String m) { super(m); } }

    /** 完整流程：環境檢查 → 驗證資源 → 取得獨占鎖 → 初始化 → 摘要。 */
    public Summary seed(Path dir) throws Exception {
        if (!sandbox.enabled()) throw new Refused("只有 SHOP_SANDBOX_MODE=true 的公開沙盒可以執行初始化");
        String database = db.queryForObject("SELECT DATABASE()", String.class);
        if (!context.getEnvironment().matchesProfiles("demo") || !DemoMigrationGuard.DEMO_DATABASES.contains(database))
            throw new Refused("只允許 demo profile 與隔離的 figura_shop_demo 資料庫");
        var catalogData = SandboxSeedCatalog.load(dir, json);   // 全部驗證通過才會往下寫入
        try (Connection lock = dataSource.getConnection()) {
            try (var st = lock.prepareStatement("SELECT GET_LOCK(?,0)")) {
                st.setString(1, LOCK);
                try (var rs = st.executeQuery()) { if (!rs.next() || rs.getInt(1) != 1) throw new Refused("另一個沙盒初始化正在執行"); }
            }
            try {
                preflight(catalogData);   // 與既有資料的衝突在任何寫入之前拒絕
                var actor = enableActor();
                try { return populate(catalogData, actor); }
                catch (SandboxSeedCatalog.Invalid e) {
                    // 驗證後檔案才被變更：前面的步驟可能已提交，不能回報「未寫入」
                    throw new IllegalStateException(e.getMessage() + "（初始化途中發生，已完成的步驟保留；請還原 seed 後重跑）");
                }
                finally { disableActor(actor.getId()); }
            } finally {
                try (var st = lock.prepareStatement("SELECT RELEASE_LOCK(?)")) { st.setString(1, LOCK); st.execute(); }
            }
        }
    }

    // ---- 系統員工：只有初始化所需權限，執行期間啟用、結束即停用 ----
    /**
     * 寫入前核對既有資料：同 code 的商品必須是先前由同類 seed 建立（名稱相同、AI_CONCEPT、description 以 AI 標示開頭），
     * 新會員的手機不可已被他人使用；否則拒絕，不接手非本初始化建立的資料。
     */
    private void preflight(SandboxSeedCatalog data) {
        for (var item : data.items()) {
            var rows = db.queryForList("SELECT p.title,p.description,p.content_origin FROM catalog_sku s JOIN catalog_product p ON p.id=s.product_id WHERE s.code=?", item.code());
            if (rows.isEmpty()) continue;
            var r = rows.get(0);
            if (!item.title().equals(r.get("title")) || !"AI_CONCEPT".equals(r.get("content_origin"))
                    || !String.valueOf(r.get("description")).startsWith(SandboxSeedCatalog.AI_MARKER))
                throw new Refused(item.code() + " 已存在但不是這份 seed 建立的展示商品；請使用新的空沙盒 DB");
        }
        for (var c : data.customers()) {
            if (c.builtin() || customers.byEmail(c.email()) != null) continue;
            if (customers.byPhone(c.phone()) != null) throw new Refused("會員 " + c.key() + " 的手機已被其他會員使用；請使用新的空沙盒 DB");
        }
    }

    private CustomUserDetails enableActor() {
        String unusable = passwords.encode(randomText(40));   // 沒有人知道、也不保存的密碼；登入另需 MFA
        Integer id = tx.execute(st -> {
            // 鎖序與員工治理一致：guard → 員工 → 角色／授權
            db.queryForObject("SELECT id FROM staff_access_guard WHERE id=1 FOR UPDATE", Integer.class);
            var users = db.queryForList("SELECT id FROM user WHERE username=? FOR UPDATE", Integer.class, ACTOR);
            if (!users.isEmpty()) {
                // 只接手先前由本入口建立的系統帳號（只有「沙盒初始化」角色或沒有角色）
                int foreign = db.queryForObject("SELECT COUNT(*) FROM user_role ur JOIN role r ON r.id=ur.role_id WHERE ur.user_id=? AND NOT (r.name=? AND r.description=?)",
                        Integer.class, users.get(0), ROLE_NAME, ROLE_DESCRIPTION);
                if (foreign > 0) throw new Refused("已有同名員工 " + ACTOR + " 且不是沙盒初始化系統帳號；拒絕接手");
            }
            var roleIds = db.queryForList("SELECT id FROM role WHERE name=? AND description=? ORDER BY id LIMIT 1", Integer.class, ROLE_NAME, ROLE_DESCRIPTION);
            int role;
            if (roleIds.isEmpty()) {
                role = db.queryForObject("SELECT COALESCE(MAX(id),0)+1 FROM role", Integer.class);
                db.update("INSERT INTO role(id,name,description,enabled,version) VALUES(?,?,?,TRUE,0)", role, ROLE_NAME, ROLE_DESCRIPTION);
            } else role = roleIds.get(0);
            db.update("UPDATE role SET enabled=TRUE WHERE id=?", role);
            String in = String.join(",", Collections.nCopies(ROLE_PERMISSIONS.size(), "?"));
            var params = new ArrayList<Object>(); params.add(role); params.addAll(ROLE_PERMISSIONS);
            db.update("DELETE FROM role_permission WHERE role_id=? AND permission_id NOT IN (SELECT id FROM permissions_backend WHERE code IN (" + in + "))", params.toArray());
            db.update("INSERT IGNORE INTO role_permission(role_id,permission_id) SELECT ?,id FROM permissions_backend WHERE code IN (" + in + ")", params.toArray());
            // 系統角色只屬於系統帳號：若被指派給其他員工，改寫授權會讓對方既有 session 取得權限 → 拒絕（整個交易回滾）
            if (db.queryForObject("SELECT COUNT(*) FROM user_role ur JOIN user u ON u.id=ur.user_id WHERE ur.role_id=? AND u.username<>?", Integer.class, role, ACTOR) > 0)
                throw new Refused("「" + ROLE_NAME + "」角色已被指派給其他員工；拒絕修改其授權");
            int user;
            if (users.isEmpty()) {
                db.update("INSERT INTO user(username,password,first_name,last_name,role,is_stop,created_time,modified_time) VALUES(?,?,?,'','S','F',NOW(),NOW())", ACTOR, unusable, "沙盒初始化");
                user = db.queryForObject("SELECT id FROM user WHERE username=?", Integer.class, ACTOR);
            } else {
                user = users.get(0);
                db.update("UPDATE user SET is_stop='F',password=?,auth_version=auth_version+1,modified_time=NOW() WHERE id=?", unusable, user);   // 撤銷任何舊 session
            }
            db.update("DELETE FROM user_role WHERE user_id=?", user);
            db.update("INSERT INTO user_role(user_id,role_id) VALUES(?,?)", user, role);
            return user;
        });
        return new CustomUserDetails(ACTOR, id, List.of());
    }
    private void disableActor(int id) {
        tx.executeWithoutResult(st -> {
            db.queryForObject("SELECT id FROM staff_access_guard WHERE id=1 FOR UPDATE", Integer.class);
            db.update("UPDATE user SET is_stop='T',auth_version=auth_version+1,modified_time=NOW() WHERE id=?", id);
        });
    }

    // ---- 初始化 ----
    private Summary populate(SandboxSeedCatalog data, CustomUserDetails actor) {
        LocalDate today = LocalDate.now(TAIPEI);
        var masterIds = new HashMap<String, String>();
        for (var item : buildOrder(data.items())) {
            String brand = master("BRAND", item.brand(), actor, masterIds), work = master("WORK", item.series(), actor, masterIds),
                    category = master("CATEGORY", item.category(), actor, masterIds);
            product(item, brand, category, work, actor, today);
        }
        var customerIds = new HashMap<String, String>();
        for (var c : data.customers()) customerIds.put(c.key(), customer(c));
        var byCode = new HashMap<String, SandboxSeedCatalog.Item>(); data.items().forEach(i -> byCode.put(i.code(), i));
        var keys = orderKeys(data, customerIds);
        for (int i = 0; i < data.orders().size(); i++) order(keys.get(i), data.orders().get(i), customerIds.get(data.orders().get(i).customer()), actor);
        return summary(data, customerIds, keys);
    }

    /** 建立順序：依 series 交錯排列後反向建立，讓「最新」列表混合各系列（與現行 HTTP seed 相同）。 */
    static List<SandboxSeedCatalog.Item> buildOrder(List<SandboxSeedCatalog.Item> items) {
        var groups = new LinkedHashMap<String, Deque<SandboxSeedCatalog.Item>>();
        for (var i : items) groups.computeIfAbsent(i.series(), k -> new ArrayDeque<>()).add(i);
        var out = new ArrayList<SandboxSeedCatalog.Item>();
        while (out.size() < items.size()) for (var q : groups.values()) if (!q.isEmpty()) out.add(q.poll());
        Collections.reverse(out);
        return out;
    }

    private String master(String kind, String name, CustomUserDetails actor, Map<String, String> cache) {
        String key = kind + "|" + name;
        String cached = cache.get(key);
        if (cached != null) return cached;
        String table = switch (kind) { case "BRAND" -> "catalog_brand"; case "WORK" -> "catalog_work"; default -> "catalog_category"; };
        var ids = db.queryForList("SELECT id FROM " + table + " WHERE name=?", String.class, name);
        String id = ids.isEmpty()
                ? masters.save(kind, null, new MasterPayloads.Write(name, MASTER_DESCRIPTION, true, 0, uuid("master|" + key), "沙盒初始化建立主檔"), actor).current().id()
                : ids.get(0);
        cache.put(key, id);
        return id;
    }

    private void product(SandboxSeedCatalog.Item item, String brand, String category, String work, CustomUserDetails actor, LocalDate today) {
        var sku = db.queryForList("SELECT id,product_id FROM catalog_sku WHERE code=?", item.code());
        String productId, skuId;
        if (sku.isEmpty()) {
            // AI 標示：description 以標示開頭，並標記 contentOrigin=AI_CONCEPT；其他規格欄位一律不填（不杜撰）
            var specs = new CatalogPayloads.Specs(null, null, null, null, null, null, null, null, null, null, null, null, null, "AI_CONCEPT", null);
            var view = catalog.create(new CatalogPayloads.Write(item.title(), null, null, item.description(), 0,
                    List.of(new CatalogPayloads.Sku(null, item.code(), item.variant(), item.priceTwd(), true)), brand, category, work, specs), actor);
            productId = view.id(); skuId = view.skus().get(0).id();
        } else { productId = (String) sku.get(0).get("product_id"); skuId = (String) sku.get(0).get("id"); }

        var row = db.queryForMap("SELECT status,version FROM catalog_product WHERE id=?", productId);
        var present = new HashSet<>(db.queryForList("SELECT image_type FROM product_image_gallery WHERE product_id=?", String.class, productId));
        if ("DRAFT".equals(row.get("status"))) {
            for (var image : item.images()) {
                if (present.contains(image.type())) continue;   // 已存在的圖片類型不重複建立
                int version = version(productId);
                gallery.upload(productId, new GalleryPayloads.Upload(uuid("image|" + productId + "|" + image.type()), version, actor.getId(), image.alt(),
                        GalleryPayloads.Type.valueOf(image.type()), "ADD", null), new Bytes(image.file().getFileName().toString(), image.contentType(), image.read()), actor);
            }
            catalog.publication(productId, new CatalogPayloads.Publication(version(productId), true), actor);
        } else if (!"PUBLISHED".equals(row.get("status"))) {
            throw new IllegalStateException(item.code() + " 商品狀態為 " + row.get("status") + "，無法初始化");
        } else if (!present.containsAll(SandboxSeedCatalog.IMAGE_TYPES)) {
            throw new IllegalStateException(item.code() + " 已上架但圖片不完整；請以新的空沙盒 DB 重新初始化");
        }

        if (item.stock() > 0) {
            String request = uuid("stock|" + skuId);
            if (db.queryForObject("SELECT COUNT(*) FROM inventory_adjustment WHERE request_id=?", Integer.class, request) == 0) {
                var balance = inventory.balance(productId, skuId);
                inventory.adjust(productId, skuId, new InventoryPayloads.Adjust(request, balance.version(), item.stock(), "RESTOCK", "沙盒展示庫存"), actor);
            }
        }
        if (item.preorder() != null) {
            var p = item.preorder();
            var existing = db.queryForList("SELECT id,status,version FROM preorder_batch WHERE sku_id=? ORDER BY created_at LIMIT 1", skuId);
            String batchId; String status; int version;
            if (existing.isEmpty()) {
                var time = LocalTime.parse(p.deadlineTime().substring(0, 5));
                var deadline = OffsetDateTime.of(today.plusDays(p.deadlineDays()), time, ZoneOffset.ofHours(8));
                var applied = batches.save(null, new PreorderPayloads.Write(productId, skuId, item.title() + "｜" + item.series() + "展示預購",
                        "《" + item.series() + "》AI 原創展示商品，價格與配額供商店功能演示。", item.priceTwd(), p.capacity(), deadline,
                        today.plusDays(p.arrivalFromDays()), today.plusDays(p.arrivalToDays()), 0, uuid("preorder|" + skuId), "沙盒初始化建立預購批次", null, null, null), actor);
                batchId = applied.current().id(); status = applied.current().status(); version = applied.current().version();
            } else {
                batchId = (String) existing.get(0).get("id"); status = (String) existing.get(0).get("status"); version = ((Number) existing.get(0).get("version")).intValue();
            }
            if ("DRAFT".equals(status)) batches.command(batchId, "OPEN", new PreorderPayloads.Command(version, uuid("preorder-open|" + skuId), "沙盒初始化開放預購"), actor);
        }
    }
    private int version(String productId) { return db.queryForObject("SELECT version FROM catalog_product WHERE id=?", Integer.class, productId); }

    /** builtin 會員由 API 以私有環境密碼建立（必須已存在）；其他會員以執行時隨機密碼註冊（不輸出、不保存明文）。 */
    private String customer(SandboxSeedCatalog.Customer c) {
        var existing = customers.byEmail(c.email());
        if (existing != null) return existing.getId();
        if (c.builtin()) throw new IllegalStateException("內建示範會員不存在（需由 API 以 DEMO_CUSTOMER_PASSWORD 建立）：" + c.key());
        return registration.register(new CustomerPayloads.Registration(randomText(24), c.email(), c.phone())).getId();
    }

    /**
     * 訂單操作編號：綁定會員 ID 與訂單內容（目標狀態、品項、數量），相同內容的第 n 筆以序號區分；
     * 調整 seed 中訂單的排列順序不會重複建立，也不會把不同內容誤認成已完成的訂單。
     */
    static List<String> orderKeys(SandboxSeedCatalog data, Map<String, String> customerIds) {
        var seen = new HashMap<String, Integer>(); var keys = new ArrayList<String>();
        for (var o : data.orders()) {
            var lines = new StringBuilder();
            o.lines().stream().sorted(Comparator.comparing(SandboxSeedCatalog.OrderLine::code)).forEach(l -> lines.append(l.code()).append('x').append(l.quantity()).append(';'));
            String base = "order|" + customerIds.get(o.customer()) + "|" + o.state() + "|" + lines;
            keys.add(base + "|" + seen.merge(base, 1, Integer::sum));
        }
        return keys;
    }
    /** 每位會員一個固定編號的示範地址；重跑時沿用目前版本（地址已提交、下單前中斷的情況）。 */
    public static String addressId(String customerId) { return uuid("address|" + customerId); }

    private void order(String key, SandboxSeedCatalog.Order o, String customer, CustomUserDetails actor) {
        String placeRequest = uuid(key + "|place");
        var prior = db.queryForList("SELECT id FROM customer_order WHERE customer_id=? AND request_id=?", String.class, customer, placeRequest);
        String orderId;
        if (prior.isEmpty()) {
            String address = addressId(customer);
            var existing = db.queryForList("SELECT version,deleted FROM customer_address WHERE id=? AND customer_id=?", address, customer);
            int addressVersion;
            if (existing.isEmpty())
                addressVersion = shopping.saveAddress(customer, address, new ShoppingPayloads.AddressWrite(0, "虛構收件人", "0900000000", "100", "範例市", "展示區", "沙盒路 1 號")).version();
            else if (truthy(existing.get(0).get("deleted")))
                throw new IllegalStateException("示範會員的地址已被刪除；請使用新的空沙盒 DB 重新初始化");
            else addressVersion = ((Number) existing.get(0).get("version")).intValue();
            // 購物車先清掉不屬於這筆訂單的品項（例如上次中斷時殘留），再設定本訂單的絕對數量
            var wanted = new LinkedHashMap<String, Integer>();
            for (var line : o.lines()) wanted.put(db.queryForObject("SELECT id FROM catalog_sku WHERE code=?", String.class, line.code()), line.quantity());
            for (var existingLine : shopping.cart(customer).items())
                if (!wanted.containsKey(existingLine.skuId())) shopping.removeItem(customer, existingLine.skuId(), shopping.cart(customer).version());
            wanted.forEach((skuId, qty) -> shopping.setItem(customer, skuId, new ShoppingPayloads.CartWrite(shopping.cart(customer).version(), qty)));
            var cart = shopping.cart(customer);
            var actual = new HashMap<String, Integer>(); cart.items().forEach(l -> actual.put(l.skuId(), l.quantity()));
            if (!actual.equals(wanted)) throw new IllegalStateException("示範訂單的購物車內容與 seed 不符，未下單");
            orderId = orders.place(customer, new OrderPayloads.Place(placeRequest, cart.version(), address, addressVersion, cart.subtotalTwd())).order().id();
        } else orderId = prior.get(0);
        // 依訂單「目前」狀態接續：已完成的步驟略過（既有 service 的重播核對原始版本，不能拿新版本重送同一編號）
        String state = o.state();
        if (!Set.of("PENDING_PAYMENT", "PAID").contains(status(orderId)))
            throw new IllegalStateException("示範訂單已進入終態 " + status(orderId) + "（初始化中斷過久），不能接續；請使用新的空沙盒 DB 重新初始化");
        if (status(orderId).equals("PENDING_PAYMENT") && !truthy(db.queryForObject("SELECT expires_at>NOW(6) FROM customer_order WHERE id=?", Object.class, orderId)))
            throw new IllegalStateException("示範訂單已超過付款期限（初始化中斷過久），不能接續；請使用新的空沙盒 DB 重新初始化");
        if (state.equals("PENDING_PAYMENT")) return;
        if (status(orderId).equals("PENDING_PAYMENT")) {
            var started = payments.start(customer, orderId, new PaymentPayloads.Start(uuid(key + "|pay"), orders.detail(customer, orderId).version()));
            payments.simulate(customer, started.payment().id(), new PaymentPayloads.Simulate(uuid(key + "|pay-event"), "SUCCESS"));
        }
        if (!status(orderId).equals("PAID")) throw new IllegalStateException("示範訂單付款後狀態不正確：" + status(orderId));
        if (state.equals("PAID")) return;
        if (fulfillmentStatus(orderId).equals("UNFULFILLED"))
            fulfillment.change(orderId, "SHIP", new dev.jackteng.figura.dto.FulfillmentPayloads.Change(uuid(key + "|ship"), orders.detail(customer, orderId).version(), "沙盒模擬出貨"), actor);
        if (state.equals("SHIPPED")) return;
        if (fulfillmentStatus(orderId).equals("SHIPPED"))
            fulfillment.change(orderId, "DELIVER", new dev.jackteng.figura.dto.FulfillmentPayloads.Change(uuid(key + "|deliver"), orders.detail(customer, orderId).version(), "沙盒模擬送達"), actor);
        if (state.equals("DELIVERED")) return;
        if (db.queryForObject("SELECT COUNT(*) FROM mock_order_return WHERE order_id=?", Integer.class, orderId) == 0)
            returns.request(customer, orderId, new ReturnPayloads.Request(uuid(key + "|return"), orders.detail(customer, orderId).version(), "尺寸與想像不同（虛構退貨原因）"));
    }
    private String status(String orderId) { return db.queryForObject("SELECT status FROM customer_order WHERE id=?", String.class, orderId); }
    private String fulfillmentStatus(String orderId) { return db.queryForObject("SELECT fulfillment_status FROM customer_order WHERE id=?", String.class, orderId); }

    private Summary summary(SandboxSeedCatalog data, Map<String, String> customerIds, List<String> keys) {
        var codes = data.items().stream().map(SandboxSeedCatalog.Item::code).toList();
        String in = String.join(",", Collections.nCopies(codes.size(), "?"));
        Object[] args = codes.toArray();
        int products = db.queryForObject("SELECT COUNT(DISTINCT product_id) FROM catalog_sku WHERE code IN (" + in + ")", Integer.class, args);
        int published = db.queryForObject("SELECT COUNT(DISTINCT p.id) FROM catalog_product p JOIN catalog_sku s ON s.product_id=p.id WHERE p.status='PUBLISHED' AND s.code IN (" + in + ")", Integer.class, args);
        int preorders = db.queryForObject("SELECT COUNT(*) FROM preorder_batch b JOIN catalog_sku s ON s.id=b.sku_id WHERE b.status='OPEN' AND s.code IN (" + in + ")", Integer.class, args);
        var images = new LinkedHashMap<String, Integer>();
        for (String type : SandboxSeedCatalog.IMAGE_TYPES) {
            var params = new ArrayList<Object>(); params.add(type); params.addAll(codes);
            images.put(type, db.queryForObject("SELECT COUNT(*) FROM product_image_gallery g JOIN catalog_sku s ON s.product_id=g.product_id WHERE g.image_type=? AND s.code IN (" + in + ")", Integer.class, params.toArray()));
        }
        // 每筆示範訂單必須存在且已到達目標狀態（付款、配送、退貨）；例如已逾時的待付款訂單不算完成
        int ordersDone = 0;
        for (int i = 0; i < data.orders().size(); i++) {
            var o = data.orders().get(i);
            var rows = db.queryForList("SELECT o.status,o.fulfillment_status,(o.expires_at>NOW(6)) live,(SELECT COUNT(*) FROM mock_order_return r WHERE r.order_id=o.id) ret,(SELECT COUNT(*) FROM mock_order_return r WHERE r.order_id=o.id AND r.status='REQUESTED') requested FROM customer_order o WHERE o.customer_id=? AND o.request_id=?",
                    customerIds.get(o.customer()), uuid(keys.get(i) + "|place"));
            if (rows.size() == 1 && reached(o.state(), rows.get(0))) ordersDone++;
        }
        boolean complete = products == codes.size() && published == codes.size() && ordersDone == data.orders().size()
                && preorders == (int) data.items().stream().filter(i -> i.preorder() != null).count()
                && images.values().stream().allMatch(n -> n == codes.size());
        if (!complete) throw new IllegalStateException("初始化後核對不完整：" + products + " 件商品、" + published + " 件上架、" + preorders + " 個預購、" + ordersDone + " 筆訂單、圖片 " + images);
        return new Summary("SEEDED", products, published, preorders, ordersDone, images, data.fingerprint());
    }

    static boolean reached(String target, Map<String, Object> r) {
        String status = (String) r.get("status"), fulfillment = (String) r.get("fulfillment_status");
        boolean returned = ((Number) r.get("ret")).intValue() > 0, requested = ((Number) r.get("requested")).intValue() > 0;
        return switch (target) {
            case "PENDING_PAYMENT" -> status.equals("PENDING_PAYMENT") && truthy(r.get("live"));   // 已過期限（排程尚未處理）也不算完成
            case "PAID" -> status.equals("PAID") && fulfillment.equals("UNFULFILLED");
            case "SHIPPED" -> status.equals("PAID") && fulfillment.equals("SHIPPED");
            case "DELIVERED" -> status.equals("PAID") && fulfillment.equals("DELIVERED") && !returned;
            default -> status.equals("PAID") && fulfillment.equals("DELIVERED") && requested;   // 退貨仍在申請中（未拒絕、未驗收）
        };
    }
    private static boolean truthy(Object v) { return Boolean.TRUE.equals(v) || (v instanceof Number n && n.intValue() != 0); }

    /** 由 seed 內容決定的操作編號（UUID v3）：重跑時同一步驟使用同一編號，既有 service 會重播而不是重複建立。 */
    static String uuid(String key) { return UUID.nameUUIDFromBytes(("figura-sandbox-seed|" + key).getBytes(StandardCharsets.UTF_8)).toString(); }
    private String randomText(int n) {
        String a = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        var b = new StringBuilder(n); for (int i = 0; i < n; i++) b.append(a.charAt(random.nextInt(a.length()))); return b.toString();
    }

    /** 把已核對的 seed 圖片交給既有圖片 service（與網站上傳走同一個解碼、重編碼、稽核流程）。 */
    record Bytes(String name, String type, byte[] data) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return name; }
        @Override public String getContentType() { return type; }
        @Override public boolean isEmpty() { return data.length == 0; }
        @Override public long getSize() { return data.length; }
        @Override public byte[] getBytes() { return data.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(data); }
        @Override public void transferTo(java.io.File dest) { throw new UnsupportedOperationException(); }
    }
}
