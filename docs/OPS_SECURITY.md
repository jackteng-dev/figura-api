# 後台安全與營運（Commerce V2 Phase 7，V30）

## 員工雙因素（TOTP）

- 自願啟用：未啟用者登入不變。`GET /admin/mfa` 狀態；`POST /admin/mfa/setup` 產生密鑰（回 `secret` 與 `otpauthUri`，用 Google Authenticator 等掃描）；`POST /admin/mfa/confirm {code}` 啟用；`POST /admin/mfa/disable {code}` 停用。任何已登入員工都能管理自己的 MFA，不需額外權限。
- 啟用後 `POST /admin/login` 密碼正確只回 `{mfaRequired: true, mfaChallenge}`、**不發 session**；`POST /admin/login/mfa {challenge, code}` 驗證後才發 Cookie。challenge 5 分鐘有效、一次性、錯 5 次失效；同一時間窗的驗證碼不能重用；帳號狀態與權限在第二步重新核對。
- 密鑰以 AES-GCM 加密存在 `staff_mfa.secret_enc`，加密金鑰 `MFA_ENCRYPTION_KEY`（至少 32 字元）只放私有環境。**未設定時無法啟用 MFA**（`available=false`）。金鑰遺失或更換會讓所有已啟用者無法驗證：只能由 DBA 在受控程序中刪除對應 `staff_mfa` 列，讓該員工重新設定。
- 稽核：`staff_mfa_event`（SETUP／ENABLE／DISABLE／LOGIN_OK／LOGIN_FAIL）。
- 尚未做（待作者決定）：強制特定角色必須啟用、備用救援碼、顧客 MFA。

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
| `MFA_ENCRYPTION_KEY` | TOTP 密鑰加密 | 私有 env；**不可遺失**，需另行離線保存 |
| mock 付款 HMAC、綠界金鑰 | 付款通知驗證 | 私有 env |
| S3（SeaweedFS）存取金鑰 | 商品圖片 | images initializer 產生的私有 env |

規則：不寫入 Git、不印在日誌、不放 URL；`.env`／`demo.env` 只在忽略路徑。更換時先在隔離環境演練。
