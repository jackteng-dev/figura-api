# 後台安全與營運（Commerce V2 Phase 7，V30–V33）

## 員工雙因素（TOTP，強制，V30＋V31）

**政策**：所有員工（系統管理員、店長、一般員工）都必須綁定驗證器 App；顧客（`/api/customer`、`/api/mobile/customer`）完全不受影響。

### 登入流程

| 步驟 | API | 結果 |
|---|---|---|
| 1 密碼 | `POST /admin/login`（帳號、密碼、deviceId） | **不發 session**。已綁定 → `{mfaRequired, mfaChallenge}`（5 分鐘）；未綁定 → `{mfaSetupRequired, mfaSetupToken}`（15 分鐘） |
| 2a 驗證 | `POST /admin/login/mfa {challenge, code}` | `code` 為 6 位數 TOTP 或一組備援碼（`XXXXX-XXXXX`）→ 發 session |
| 2b 綁定 | `POST /admin/login/mfa/setup {setupToken}` → `{secret, otpauthUri}`；`POST /admin/login/mfa/enroll {setupToken, code}` | 驗證碼正確 → 啟用、發 session，回傳 10 組只顯示一次的 `backupCodes` |

- session 的 JWT 帶 `mfa=TOTP` 標記；`authenticate` 與 `refresh` 都要求這個標記。**部署前發出的 session 沒有標記，部署後一律失效**，員工需重新登入。
- 綁定 token 與 challenge 是不同的 Redis key 前綴，不能互用；都在第一次成功時原子消耗。
- 每個 token／challenge 最多 5 次嘗試（Redis Lua 原子扣次，期限不延長）；帳號 15 分鐘內最多 10 次，超過 429，連正確驗證碼也拒絕。同一時間窗的 TOTP 只能用一次。失敗、成功、綁定、備援碼使用、重設都寫 `staff_mfa_event`（失敗稽核在獨立交易，不會隨例外回滾）。
- 沒有任何不經第二因素就發 session 的 API；自行停用 MFA 的端點已移除。

### 金鑰未設定

`MFA_ENCRYPTION_KEY`（≥32 字元）未設定時：員工登入回 **503「後台雙因素驗證尚未設定，暫停員工登入」**，啟動時寫 ERROR 日誌，`/admin/ops/status` 的 `mfaAvailable=false`。**不會降級為單因素。** 顧客購物與登入不受影響。

### 備援碼與復原

- 綁定時產生 10 組一次性備援碼，只存 HMAC-SHA256（金鑰衍生自 `MFA_ENCRYPTION_KEY`）。已登入員工可在「帳號安全」以目前 TOTP 重新產生（不接受備援碼；舊碼在同一交易刪除、立即失效）。
- **下載與保存確認（V32）**：原始備援碼只在綁定或重新產生的那一次回應中出現（附帳號與產生時間），Web 以該回應在瀏覽器端產生 `Figura-MFA-Recovery-Codes.txt`（UTF-8，含標題、帳號、產生日期、10 組碼、保存提醒）並觸發下載；Blob URL 立即釋放，不經伺服器、不放 URL、不寫入 localStorage／sessionStorage／pinia、伺服器不記錄。之後無法再取得原始碼，只能重新產生。
- **必須保存者**：具 `access.mfa.reset` 權限的員工（系統管理員／店長，依權限判斷不寫死帳號）。每次產生備援碼都有新的批次 ID（`staff_mfa.backup_batch_id`，V33）；是否待確認**依目前權限即時判斷**：具該權限且（`backup_batch_id` 為空，或 `backup_confirmed_batch_id ≠ backup_batch_id`）。部署前已綁定、沒有批次的帳號因此也會被要求重新產生並保存。因此員工被升權後即使先前略過，也會立即被要求重新產生、下載並確認。在 `POST /admin/mfa/backup-codes/ack {batchId, downloaded:true, confirmed:true}`（batchId 必須是目前這批，舊批次 409）之前，除 `GET /auth/me`、`GET /admin/mfa`、`POST /admin/mfa/backup-codes`、`/ack` 外的 API 一律 403（`errorCode=MFA_BACKUP_ACK_REQUIRED`），前端導向「帳號安全」。改網址、重新整理或重新登入都無法略過；原始碼已離開畫面時需重新產生再下載。
- **其他員工**：可下載或略過（`{batchId, downloaded:false}` 寫 `BACKUP_SKIP` 稽核），不阻擋。
- V32 的 `backup_ack_pending` 欄位已由 V33 批次機制取代，不再使用（保留以維持已套用 migration 的 checksum）。
- **限制**：伺服器只能記錄「頁面已觸發下載並勾選確認」（`BACKUP_ACK`），**無法確認檔案確實存到安全位置**；稽核與介面文字都依此表述。
- 遺失手機：用備援碼登入，再到帳號安全重新產生備援碼；換手機需請管理員重設。
- 手機與備援碼都遺失：有 `access.mfa.reset` 權限的管理員在「員工管理」按「重設雙因素」（`POST /admin/access/staff/{id}/mfa/reset {requestId, note}`）。刪除對方密鑰與備援碼、`auth_version+1` 立即撤銷對方所有 session，對方下次登入重新綁定。**不能重設自己**；同 requestId 冪等；寫稽核（actor、原因）。
- **最後一位管理員也遺失**（沒有其他人能重設）：由 DBA 在受控程序中處理——備份後在 DB 執行 `DELETE FROM staff_mfa_backup_code WHERE user_id=?; DELETE FROM staff_mfa WHERE user_id=?; UPDATE user SET auth_version=auth_version+1 WHERE id=?;`，並在變更紀錄寫明原因。建議至少維持兩位具重設權限的管理員。

### 部署與避免鎖死

1. 部署前在私有 env 設定 `MFA_ENCRYPTION_KEY`（`openssl rand -base64 48`），**同時離線保存一份**（遺失或更換 = 所有人的密鑰與備援碼失效，只能逐一重設）。
2. 部署後所有既有員工的 session 失效；每位員工第一次以密碼登入會直接進入綁定畫面，**不會被鎖在外面**。
3. 部署後立即讓兩位管理員先完成綁定並保存備援碼，再通知其他員工。
4. 追蹤 `/admin/ops/status` 的 `staffActive` 與 `staffMfaEnrolled`。
5. 風險說明：綁定採「首次登入綁定」，尚未綁定的帳號若密碼外洩，持有密碼者可能搶先綁定。因此部署後應儘快完成所有員工綁定；有疑慮的帳號先重設密碼與 MFA。
6. 回退：程式回退到不強制 MFA 的版本前，須先評估安全影響；V31 只新增資料表與權限，回退程式不需動 schema。

權限：`access.mfa.reset`（285）預設只給角色 1（系統管理員／店長）。

## 會員／員工隔離（現況確認）

顧客與員工使用不同的資料表、Cookie、JWT realm 與 Redis namespace（見 AGENTS.md「顧客身分」）。員工 API 由 `JwtAuthenticationFilter` 依 `permissions_backend` 的 URL＋方法即時比對；顧客 API 只允許 `customerRouteAllowed` 白名單路徑。本階段新增的所有顧客端點都已加入白名單並以 `CustomerPrincipal` 取本人資料。

## 稽核紀錄（現有＋本次新增）

商品／庫存（`inventory_adjustment` 等）、訂單、付款、退款、退貨、配送、通知、主檔、員工治理（V12）既有稽核表；Commerce V2 新增：`site_page_event`、`support_ticket_event`、`shipment_ops_event`、`shipment_customer_event`、`staff_mfa_event`。全部沒有刪改 API。

## 營運報表與低庫存

| 方法 | 路徑 | 權限 | 說明 |
|---|---|---|---|
| GET | `/admin/reports/sales?from&to` | `reports.sales`（店長） | 依付款來源分列：現貨模擬、綠界測試環境、預購模擬；`realRevenueTwd` 只計正式金流（`shop.payments.ecpay.live=true` 才算綠界為正式），目前為 0；前 10 名商品（已付款且未退款） |
| GET | `/admin/reports/low-stock?threshold=3` | `reports.low-stock`（店長、員工） | 可售量（實體−保留）≤ 門檻的啟用款式 |
| GET | `/admin/ops/status` | `ops.status`（店長） | DB、Redis、通知佇列各狀態數、逾期暫存、待審運費調整、待回覆客服單、送審中頁面、伺服器錯誤累計與最近 50 筆 |

## 錯誤監控

未處理例外現在會：寫 ERROR 日誌（含錯誤代碼、方法、路徑與 stack trace）、回給使用者「錯誤代碼 xxxxxxxx」、記入記憶體計數（`/admin/ops/status`）。不含請求內容或個資。重啟後計數歸零；長期監控請接日誌收集（伺服器現有 log 或免費自架方案，如 Loki／Grafana），屬部署端設定。

## 資料庫與圖片備份還原

**原則**：只在正式主機上由作者執行；任何還原先還原到隔離環境驗證，不直接覆蓋正式 DB。

MySQL（每日，保留 14 份）：

```bash
mysqldump --single-transaction --routines --triggers --hex-blob \
  -h <host> -u <backup_user> -p figura_shop > figura-$(date +%F).sql
gzip figura-$(date +%F).sql
sha256sum figura-$(date +%F).sql.gz > figura-$(date +%F).sql.gz.sha256
```

- 備份帳號只給 `SELECT, SHOW VIEW, TRIGGER, LOCK TABLES, EVENT, PROCESS` 權限。
- 還原演練：`docker run` 一個全新 MySQL 8.4 → `gunzip -c ... | mysql` → 啟動 API 指向該 DB（`DB_URL`）→ Flyway `validate` 必須通過（不可 baseline／clean／repair）→ 抽查訂單、付款、庫存筆數。

SeaweedFS（商品圖片）：

```bash
# S3 相容同步到另一個 bucket 或離線磁碟
aws --endpoint-url http://<seaweedfs>:8333 s3 sync s3://<bucket> ./images-backup/$(date +%F)
```

- 圖片 key 不可變（V11／V24），增量同步即可；還原時同步回空 bucket，再以 `image` 表的 objectKey 抽查。
- DB 與圖片需同一時間點附近備份；還原後孤兒圖片由既有 cleanup queue 處理，**不要手動刪除 bucket 內物件**。

Redis 只存 session 與節流計數，不需備份；還原後所有人需重新登入。

## 密鑰管理

| 變數 | 用途 | 存放 |
|---|---|---|
| `DB_PASSWORD`、`REDIS_PASSWORD` | 資料庫／Redis | 主機私有 env（不進 Git） |
| JWT 金鑰（員工、顧客、App） | session 簽章 | 私有 env；更換會讓所有人重新登入 |
| `MFA_ENCRYPTION_KEY` | TOTP 密鑰加密、備援碼 HMAC | 私有 env；**必填**（未設定員工無法登入）；**不可遺失**，需另行離線保存 |
| mock 付款 HMAC、綠界金鑰 | 付款通知驗證 | 私有 env |
| S3（SeaweedFS）存取金鑰 | 商品圖片 | images initializer 產生的私有 env |

規則：不寫入 Git、不印在日誌、不放 URL；`.env`／`demo.env` 只在忽略路徑。更換時先在隔離環境演練。
