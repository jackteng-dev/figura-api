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
- 到期後拒絕密碼登入與既有 session。
- 清理排程每 5 分鐘刪除到期帳號，連同：
  - `staff_mfa`、備援碼、`staff_mfa_event`、`user_role`、`sandbox_demo_account`、`user`。
  - Redis 的 `shop:session:<id>:*` 與失敗計數。
- 資料表：V34 新增 `sandbox_demo_account`，只新增空表；正式環境永遠不寫入。

`GET /api/sandbox/config` 回 `{quickDemo, mfaDemo, mfaDemoTtlMinutes}`，前端據此決定是否顯示入口。不含帳號名稱或密碼，正式環境回 404。

`/api/auth/me` 回 `accountKind`（`STAFF`／`SANDBOX_SHARED`／`SANDBOX_MFA_DEMO`）。MFA 展示帳號另回 `sandboxExpiresAt`。這兩個欄位只供前端顯示，後端授權不依賴它們。

## 4. 沙盒初始化（seed）

不需員工登入的初始化入口（spec 2.4）**尚未完成**，列為下一個 PR。過渡期沿用伺服器端的 HTTP seed，但必須改用 `SANDBOX_MFA_EXEMPT_USERNAMES` 內的共用帳號登入，因為非豁免帳號會被要求 MFA。

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

自動化測試：`SandboxModeTest`、`SandboxIntegrationTest`、`SandboxDisabledIntegrationTest`。
