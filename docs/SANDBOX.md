# 公開沙盒模式（SANDBOX）

公開沙盒是作者展示用的獨立環境，例如 `https://figura.jackteng.dev`。它有自己的 DB、Redis、SeaweedFS 和金鑰。

**所有沙盒功能預設關閉**，只能由伺服器端環境變數開啟。正式商城不得設定任何 `SHOP_SANDBOX_*`／`SANDBOX_*` 變數。

## 1. 環境變數

| 變數 | 預設 | 說明 |
|---|---|---|
| `SHOP_SANDBOX_MODE` | `false` | 宣告此部署為公開沙盒，是所有沙盒功能的總開關 |
| `SANDBOX_MFA_EXEMPT_USERNAMES` | 空 | 快速展示共用帳號，以逗號分隔。登入帳號必須與設定逐字相同，大小寫和空白都要一致 |
| `SANDBOX_MFA_DEMO_ENABLED` | `false` | MFA 展示模式（短效臨時帳號） |
| `SANDBOX_MFA_DEMO_TTL_MINUTES` | `60` | 臨時帳號有效期限（5–1440） |
| `SANDBOX_MFA_DEMO_MAX_ACTIVE` | `50` | 同時存在的臨時帳號上限（1–500） |
| `SANDBOX_MFA_DEMO_CREATE_PER_HOUR` | `200` | 全站每小時建立上限（1–5000）；每 IP 限流由沙盒 proxy 負責 |
| `shop.sandbox.cleanup-delay-ms` | `300000` | 到期帳號清理間隔 |

啟動時的保護：
- 任一沙盒變數不是預設值，但 `SHOP_SANDBOX_MODE` 不是 `true` → **拒絕啟動**。
- `SHOP_SANDBOX_MODE=true` 與 `shop.payments.ecpay.live=true` 同時出現 → **拒絕啟動**。
- 布林值只接受字面 `true`／`false`；數字超出範圍，或共用帳號名稱不合法（含 `mfa-demo-` 前綴）→ **拒絕啟動**。
- 沙盒模式啟動時，日誌會以 WARN 列出啟用的功能。
- `/admin/ops/status` 回報 `sandboxMode`、`mfaExemptAccounts`（數量）和 `mfaDemoActive`。

設定只在啟動時從環境讀一次。任何請求參數、header、cookie、body 都不能開啟或改變它們。

## 2. 快速展示模式（共用帳號免 MFA）

- 帳號在 `SANDBOX_MFA_EXEMPT_USERNAMES` 內、啟用中、密碼正確 → 直接發 session。
  - 回應不含 `mfaSetupRequired`／`mfaRequired`，不產生備援碼，`accountKind=SANDBOX_SHARED`。
  - Session 的 JWT 帶 `mfa=SANDBOX_SHARED` 標記。每次請求都重新核對**目前**設定：帳號從清單移除或關閉沙盒模式後，既有 session 立即失效。
  - 正式設定下，帶這個標記的 session 一律 401。
- 大小寫或空白變形的帳號（例如 `SHOP_ADMIN`、`shop_admin `）**拒絕登入**。這樣訪客無法用變形名稱替共用帳號開始綁定 MFA，把其他人鎖在外面。
- 共用帳號**不能**做以下操作，API 回 403 `SANDBOX_SHARED_ACCOUNT`：
  - 任何 `/api/admin/mfa/**` 寫入（產生、確認備援碼）。
  - 綁定 MFA（服務層也會拒絕）。
  - 任何 `/api/admin/access/**` 寫入：員工、角色、重設 MFA，包含修改任何人的帳號或密碼。
  - 舊上傳入口。
- 其他權限照原 RBAC。沙盒 proxy 另有封鎖，作為第二道防線。
- 非豁免員工照正式規則強制 MFA。

## 3. MFA 展示模式（短效臨時帳號）

- `POST /api/sandbox/mfa-demo/accounts`（需 CSRF，不需登入）：
  - 建立 `mfa-demo-<8 碼>` 帳號與 20 字元隨機密碼，**只在這次回應出現**；伺服器只存 bcrypt。
  - 回應：`{username, password, expiresAt, ttlMinutes}`。
  - 超過同時上限或每小時上限回 429。
  - 只有 `SHOP_SANDBOX_MODE=true` 且 `SANDBOX_MFA_DEMO_ENABLED=true` 時可用，否則 404。
- 專用角色「MFA 展示」：
  - 只有 `workspace.read`、`reports.sales`、`reports.low-stock`。
  - 在沙盒第一次建立帳號時才建立。每次建立帳號都會把權限核對回這份白名單。
- 登入完全走正式 MFA 程式路徑（`StaffMfaService`）：
  - 首次密碼登入 → `mfaSetupRequired` → QR／手動金鑰 → 驗證碼 → 比照管理員強制下載並確認保存備援碼。
  - 登出再登入 → 要求 TOTP。
  - 備援碼一次性。
- 臨時帳號只能 GET（依角色權限），以及 POST 自己的 `/api/admin/mfa/backup-codes`、`/ack`。其他寫入回 403 `SANDBOX_DEMO_ACCOUNT`。
- 到期後拒絕密碼登入、MFA 完成、既有 session 與 refresh。
- **生命週期與目前沙盒設定無關**：關閉沙盒模式或 MFA 展示模式後，已建立的臨時帳號一律無法使用，不會變成一般員工。清理排程也照常執行；正式環境這張表是空的。
- 清理排程每 5 分鐘刪除到期帳號，連同：
  - `staff_mfa`、備援碼、`staff_mfa_event`、`user_role`、`sandbox_demo_account`、`user`。
  - Redis 的 `shop:session:<id>:*` 與失敗計數。
- 若臨時帳號被其他業務資料以外鍵參照（例如客服單指派）而無法刪除：
  - 改為保留停用墓碑：`is_stop='T'`、換成無人知道的密碼、`auth_version+1`。
  - 刪除 MFA、備援碼、MFA 事件、角色與臨時帳號紀錄，並清除 Redis session。
  - 參照它的業務與稽核歷史不刪除，下次排程也不會卡在同一筆。
- 資料表：V34 新增 `sandbox_demo_account`，只新增空表；正式環境永遠不寫入。

`GET /api/sandbox/config` 回 `{quickDemo, mfaDemo, mfaDemoTtlMinutes}`，前端據此決定是否顯示入口。不含帳號名稱或密碼，正式環境回 404。

`/api/auth/me` 回 `accountKind`（`STAFF`／`SANDBOX_SHARED`／`SANDBOX_MFA_DEMO`）。MFA 展示帳號另回 `sandboxExpiresAt`。這兩個欄位只供前端顯示，後端授權不依賴它們。

## 4. 沙盒初始化入口（spec 2.4）

**沒有任何 HTTP 端點。** 初始化只能由伺服器以一次性程序執行，完成後程序結束：

```
docker compose run --rm -v /usr/local/share/sandbox/figura/seed:/seed:ro api --sandbox.seed.dir=/seed
```

### 執行條件（任一不符立即失敗，不寫入任何資料）
- **Spring 啟動前的程序層預檢**（`SandboxSeedLauncher`）：參數格式、`SHOP_SANDBOX_MODE=true`、整份 seed 資源驗證。
  不通過就直接結束，**不連線資料庫**，Flyway 與其他 initializer 都不會執行。
- 通過後才啟動 Spring，且 HTTP 只綁 `127.0.0.1` 的隨機埠（命令列參數，優先於環境設定；既有的 `--server.address`／`--server.port` 參數會被移除），初始化程序不對外提供任何端點。
- Spring 內再核對：`SHOP_SANDBOX_MODE=true`、demo profile，且資料庫名稱是隔離的 `figura_shop_demo`。
- 參數只讀**命令列** `--sandbox.seed.dir`，不讀環境變數或設定檔；一般啟動（沒有這個參數）完全不動作。
- 同一時間只能執行一個：以 MySQL 具名鎖 `figura_sandbox_seed` 獨占，拿不到鎖立即失敗。

### 資源格式（schemaVersion 2）與驗證
- `catalog.json` 加上 `images/`：
  - 欄位沿用伺服器 `templates/sandbox/figura/catalog.json`：`items`、`customers`、`orders`、`aiDisclosureMarker`。
  - 每件商品加上 `images[]`：`MAIN`、`FACE`、`DETAIL`、`SCENE`、`REAR` 依序 5 張，`position` 0–4，每張有 `file`、`sha256`、`contentType`、`alt`。
- **全部驗證通過才開始寫入**：
  - 結構與數值範圍；`code` 格式 `FIGURA-AI-NNN` 不重複。
  - `description` 必須以 AI 標示開頭。
  - 訂單只能引用現貨商品，數量不超過現貨。
  - 會員 email 必須是 `example.com`／`example.test` 虛構信箱。
  - 預購最早到貨日不可早於截止日；預購商品的批次名稱（`{title}｜{series}展示預購`）不超過 120 字；新會員手機不可重複。
  - 每個圖片檔必須在 seed 目錄內：不可 `..`、不可符號連結，且是一般檔案；大小 ≤ 5MB；檔頭符合 PNG／JPEG；SHA-256 與 `sha256` 相同。
  - 讀取一律有**硬上限**（最多讀上限＋1 byte，不依賴先前的大小檢查）：catalog.json 4MB、圖片 5MB。
  - 缺檔或 SHA 不符 → exit 4。
- 驗證時串流計算 SHA，不把圖片全部放進記憶體。上傳前再核對一次路徑（不跟隨連結、仍在目錄內），讀取後重新核對 SHA。
  驗證後檔案若被更換，會在那張圖失敗；此時前面的商品可能已提交，因此回報 exit 1（不是「未寫入」的 exit 4）。
  掛載 `:ro` 不能防止宿主機修改來源，seed 目錄應由 reset 流程以不可變的發布版本提供。
- 圖片交給既有圖片 service 重新編碼（PNG 結果與原檔相同；JPEG 會改變 SHA，屬預期）。驗證的是來源檔 SHA。

### 執行方式
- 以系統員工 `sandbox-seeder` 呼叫**既有 service**；所有稽核事件的 actor 都是它。
  - 它只有初始化所需的權限，透過「沙盒初始化」角色授予：`masters.create`、`catalog.write.create`、`catalog.publish`、`catalog.images.upload`、`inventory.adjust`、`preorders.create`、`preorders.open`、`orders.ship.mock`、`orders.deliver.mock`。
  - 密碼是沒有人知道、也不保存的隨機值（每次執行重設），登入另需 MFA。
  - 只在執行期間啟用：啟用時遞增 `auth_version`（撤銷任何舊 session），結束後（含失敗）停用並再遞增。
  - 若已有同名員工但不是本入口建立的系統帳號（有其他角色），拒絕接手（exit 3）。
  - 「沙盒初始化」角色若被指派給其他員工，拒絕改寫其授權（exit 3，交易回滾），避免對方既有 session 取得權限。
  - 鎖序與員工治理相同：`staff_access_guard` → 員工 → 角色／授權。
  - 程序被強制終止（SIGKILL／OOM）時 `finally` 不會執行，帳號可能維持啟用；但密碼未知且已撤銷舊 session，無法登入。下次執行或 reset 會再停用。
- 每件商品的順序與現行 HTTP seed 相同：
  1. 主檔：依名稱沿用或建立。
  2. 草稿商品：1 個 SKU，`contentOrigin=AI_CONCEPT`，其他規格不填。
  3. 依序上傳 5 張圖（MAIN 成為主圖）。
  4. 上架。
  5. 現貨商品以 `RESTOCK` 補庫存（note「沙盒展示庫存」）。
  6. 預購商品建立批次並開放；日期相對執行當天（Asia/Taipei）。
- 建立順序：依 series 交錯排列後反向。
- 會員：
  - `builtin` 會員必須已由 API 以 `DEMO_CUSTOMER_PASSWORD` 建立，不存在就失敗。
  - 其他會員以**執行時隨機產生**的密碼註冊，不輸出、不保存明文。
- 訂單：購物車下單 → 模擬付款 → 模擬出貨 → 模擬送達 → 顧客申請模擬退貨，依 `state` 停在對應步驟。全部是模擬，沒有實際收款或物流。

### 冪等與重跑
- 寫入前核對既有資料：同 `code` 的商品必須是同類 seed 建立的（名稱相同、`AI_CONCEPT`、description 以 AI 標示開頭）；新會員手機不可已被使用。否則拒絕（exit 3），不接手。
- 所有操作編號由 seed 內容決定（UUID v3）。訂單編號綁定會員 ID 與訂單內容（目標狀態、品項、數量），調整訂單順序不會重複下單。
- 每位會員使用一個固定編號的示範地址；地址已提交、下單前中斷時，重跑沿用其目前版本。
- 下單前先清除購物車中不屬於該筆訂單的品項（例如中斷殘留），並核對購物車內容與 seed 完全相同才下單。
- 已存在的商品（以 SKU code）、圖片類型、庫存調整、預購批次、會員（以 email）、訂單（以下單編號）都不重複建立。
- 訂單依**目前狀態**接續未完成的步驟。已進入終態（例如中斷超過 30 分鐘而逾時）的訂單不復活，回報 exit 1，需以新的空沙盒 DB 重建。
- 中途失敗（exit 1）可直接重跑完成，但不是單一交易。伺服器的 reset 流程仍須「失敗就還原 last-good 快照」。

### 結果
- 成功（exit 0）時在 stdout 輸出一行 JSON 摘要，只含數量：
  ```json
  {"result":"SEEDED","products":95,"published":95,"preorders":64,"orders":8,"images":{"MAIN":95,"FACE":95,"DETAIL":95,"SCENE":95,"REAR":95},"seedSha256":"…"}
  ```
- `seedSha256` 是 catalog.json 的 SHA-256（其中列有每張圖的 SHA），可與發布的 seed 版本比對。
- 摘要前會以 DB 核對；每筆訂單必須**目前**處於目標狀態：待付款須未過付款期限；申請退貨須仍為 `REQUESTED`（未拒絕、未驗收）。數量或狀態不符時視為失敗（exit 1）。
- Exit code：0 成功；1 執行中失敗（可重跑）；2 參數錯誤；3 環境不允許（非沙盒、非 demo、非隔離 DB、另一個初始化執行中）；4 資源驗證失敗（未寫入）。

### 不在範圍
- 主檔品牌改名（`brand-rename.sql`）仍由伺服器 reset 流程處理；Seeder 建立的主檔只依 catalog 的名稱。
- 員工展示帳號與內建示範會員由 API 啟動時建立（`DEMO_*_PASSWORD`）。

## 5. 驗收

正式環境：
- 不設定任何沙盒變數。
- `shop_admin` 密碼登入 → `mfaSetupRequired=true`，且沒有 `accessToken` Cookie。
- `GET /api/sandbox/config` 與 `POST /api/sandbox/mfa-demo/accounts` → 404。
- `/admin/ops/status` 的 `sandboxMode=false`。

沙盒環境：
- 共用帳號登入直接進後台；帳號安全頁顯示「共用展示帳號：已停用安全設定變更」。
- 「體驗 MFA 安全設計」完整走完 QR 綁定 → 驗證碼 → 備援碼下載與確認 → 登出重登 → 備援碼登入。
- 超量回 429；到期帳號被拒絕並清除。

自動化測試：`SandboxModeTest`、`SandboxIntegrationTest`、`SandboxDisabledIntegrationTest`、`SandboxSeedCatalogTest`、`SandboxSeederIntegrationTest`（需 SeaweedFS）。
