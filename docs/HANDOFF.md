# Figura（原 ELPSY Shop） 後端交接

## 2026-11-18：會員架構定案（正式電商方向）

同分支 `feat/contact-only-login`，無 schema 變更、未改 `src/main/resources/`。作者決定不再保留練習版的暱稱、帳號名稱登入與舊註冊格式。

- **本人資料 `View`** 只回傳 `{id, createdAt, email, phone, emailVerified}`；不再回傳內部 `username`／`displayName`（Web／App 同步移除）。`customer.username`／`display_name` 欄位保留（NOT NULL，維持 schema 相容），由系統產生內部值，不能登入、不對外。
- **刪除無用程式**：舊會員 API `UserController`（`/api/user/*`，filter 早已回 410、不可達）；`CustomerPayloads.USERNAME`、`CustomerMapper.byUsername`、`CustomerSessionService.normalize`。`/api/user/*` 仍由 filter 回 410。員工端 `user` 表、`AdminUser*`、認證、Token、稽核未動。
- **沙盒示範會員**：`DemoCustomerSeeder`（demo profile，由 `DemoAccountInitializer` 啟動時呼叫）在提供私有 `DEMO_CUSTOMER_PASSWORD` 時建立 Email `demo-customer@example.com`、手機 `0900000000`、Email 已驗證的會員；已存在只核對手機／啟用／驗證（不符停止啟動）與密碼（不符只記錄 ERROR，因 AGENTS 不能重設既有用戶，也避免整站起不來），不修改資料；未設定就不建立。此 Email 的忘記／重設密碼在沙盒不發連結（公開的員工示範帳號能讀模擬信件，否則訪客可改掉公開密碼）。選用 `DEMO_CUSTOMER_SELF_HEAL=true` 每分鐘補建。新增 `DemoCustomerIntegrationTest`（4 項）。
- **伺服器端每日重置（不在任何 repo，需作者修改，尚未完成）**：①沙盒私有環境加 `DEMO_CUSTOMER_PASSWORD=figura-demo-customer`；②重置資料中移除舊 `demo_customer`（只有帳號名稱）列，也不要放新版示範會員；③重置流程改為「停 API → 重置資料庫 → 啟動 API」，讓啟動時建立示範會員（員工示範帳號 `shop_admin`／`shop_staff` 也只在 `user` 表為空的啟動時建立，同樣需要重啟）；④若重置無法停 API，就設 `DEMO_CUSTOMER_SELF_HEAL=true`，並確認重置資料不含示範會員；⑤重置後檢查：用 `demo-customer@example.com` 與 `0900000000` 各登入一次、API log 沒有「示範會員」ERROR。README（三個 repo）與 App Release 說明使用同一組 Email／手機／密碼（`figura-demo-customer`）。公開沙盒需設定此變數並移除舊 `demo_customer` 種子。
- **測試**：整合測試改驗 View 不含內部欄位，並以資料庫中的真實內部 username 驗證不能登入（401）。隔離 DB `mvn package`：284 項，失敗只有既有 8 項（NotificationIntegrationTest×7、PreorderOrderIntegrationTest×1；開 `MOCK_NOTIFICATIONS_ENABLED=true` 後剩 1 項，原因是測試 DB 不是空的，CI 用全新 DB 不受影響）。本機 demo 重啟後示範會員自動建立，用 Email（大小寫不同）與手機（含連字號）登入成功；HTTP smoke：customer 23、mobile 19、shopping 28、orders 24、payments 23 通過；App live 2 項、網站登入與會員頁實測通過。
- **舊會員清理**：`work/cleanup-20261118/` 改為檢查「新版示範會員存在」（Email＋手機），舊 `demo_customer` 隨其他舊帳號刪除；隔離 DB 再演練一次清理＋單一交易回復，結果相同。
## 2026-11-18：直接購買（單一款式下單，不經購物車）

分支 `feat/buy-now`（疊在 `feat/contact-only-login` 上；無 schema 變更、未改 `src/main/resources/`）。

- `POST /customer/orders/direct`（Cookie＋CSRF）：`requestId`、`productId`、`skuId`、`quantity`(1–99)、`addressId`、`addressVersion`、`expectedSubtotalTwd`。與購物車結帳共用同一建立流程（customer 鎖 → 依 product ID 排序鎖商品與 SKU → 保留庫存 → 30 分鐘期限 → 通知 outbox，同交易），但**不讀取也不清空購物車**。
- 直接購買訂單以 `cart_version=0` 標記（購物車有品項時版本必 ≥1）。同 requestId 重送需同款式／數量／地址／金額，否則 409；購物車與直接購買的 requestId 互不可沿用。
- 測試：`DirectOrderIntegrationTest` 2 項（只保留該款、購物車版本與品項不變、重送冪等、跨用途 requestId 409、庫存不足／金額不符／SKU 不屬於商品／他人地址／下架／數量越界／多餘欄位／CSRF／未登入）。訂單、購物車、付款相關測試全過。

## 2026-11-17：關閉帳號名稱登入與暱稱（只剩手機＋Email）

分支 `feat/contact-only-login`（無 schema 變更、未改 `src/main/resources/`）。作者要求：開發階段，舊帳號與相關訂單可刪。

- 註冊只接受 `phone`＋`email`＋`password`；送 `username`／`displayName` 回 400（舊格式與 Flutter App 舊註冊格式都停用）。
- 登入識別只認 Email 或台灣手機；系統產生的內部 `username`（`m_…`）與舊帳號名稱都不能登入。
- 移除暱稱修改：`PATCH /customer/me`、`/mobile/customer/me` 與 `Profile` DTO、`updateOwnDisplayName`。`username`／`display_name` 欄位仍是資料庫必填，由系統產生、不對外使用；（2026-11-18 起 `View` 不再回傳這兩欄，見最上方）。
- 測試全面改為手機＋Email 註冊（`TestPhones`），身分測試改成驗證「舊格式被拒、帳號名稱不能登入」。完整測試僅剩既有 8 個失敗。
- **HTTP smoke 腳本（2026-11-18 補）**：`scripts/smoke_*.py` 共 13 支原本仍用帳號名稱＋暱稱註冊、帳號名稱登入，在本分支會全部失敗（CI 只跑 Maven 測試，沒有跑這些腳本，所以 CI 綠燈沒有發現）。已改成手機＋Email 註冊、Email 登入；`smoke_customer.py` 改驗「舊格式註冊 400、帳號名稱登入 401、修改暱稱 403、本人資料含 Email／手機」；`smoke_mobile.py` 產生的 App fixture `APP_TEST_USERNAME` 改為 Email。本機隔離 demo（feat/buy-now 建置）實跑：customer 23、mobile 19、shopping 28、orders 24、payments 23、preorders 30 項通過；`smoke_preorder_orders` 在「MOCK 通知派送」失敗，因本機 demo 設定未開 `MOCK_NOTIFICATIONS_ENABLED`（與既有 NotificationIntegrationTest 失敗同源），註冊／登入步驟已通過。
- **沙盒示範帳號（上線前必做，伺服器端）**：每日重置建立的 `demo_customer` 沒有 Email／手機，本分支上線後**無法登入**，而且會被舊帳號清理腳本視為舊帳號刪除。（2026-11-18 更新：改由 `DemoAccountInitializer` 建立新版示範會員，見最上方；舊 `demo_customer` 種子移除即可。）員工示範帳號 `shop_admin`／`shop_staff` 不受影響。
- **Flutter App 相容**：App 需同時更新（figura-app `feat/contact-only-login`）：註冊改手機＋Email、登入接受 Email、移除修改暱稱。舊版 App 在本分支上線後：登入用手機可以（舊版欄位驗證擋 Email）、註冊與修改暱稱會失敗。
- **舊帳號刪除**：本機 demo DB 已刪 3 個舊帳號（無訂單）。正式環境需由伺服器端在部署之後、維護時段內，依 `work/cleanup-20261118/README.md` 執行（不進 Git；`backup.sh` 整庫＋逐列備份與只讀預演 → `02-cleanup.sql` 單一交易清理 → `03-verify.sql` 核對 → 清 Redis；回復用 `restore-rows.sh` 單一交易逐列回復或整庫還原）。**舊的 `work/cleanup-legacy-customers.sql` 已停用**（改名 `.SUPERSEDED.sql.txt`：沒有示範帳號保護、逐表核對與回復程序，且已出貨預購會把到貨數錯誤釋出）。2026-11-18 新版在隔離測試 DB 演練：清理→逐列回復後 24 張表 CHECKSUM 與計數和清理前完全相同。已在隔離測試 DB 以 ROLLBACK 演練 273 個舊帳號／215 訂單／67 預購，約束與庫存／配額計數全部一致。執行後另刪 Redis `shop:customer:session:<id>:*`、`shop:customer:mobile:session:<id>:*`。

## 2026-11-17：綠界（ECPay）測試環境信用卡付款（V20，含 schema 變更）

分支 `feat/ecpay`（基於 `feat/customer-email-phone`，需與前端 `feat/ecpay` 一起上線）。**只接綠界測試環境，不會實際收款**；現貨訂單限定，預購仍只有付款模擬。

- **V20**：`mock_payment` 新增 `provider`（預設 `MOCK`，可為 `ECPAY`）與 `gateway_trade_no`（綠界 TradeNo，僅 ECPAY）。純新增欄位，舊程式插入仍得到 MOCK。**需作者受控切換後才能 merge。**
- **設定（私有環境變數，預設停用，不在 yml）**：`ECPAY_ENABLED`、`ECPAY_MERCHANT_ID`、`ECPAY_HASH_KEY`、`ECPAY_HASH_IV`、`ECPAY_RETURN_URL`（綠界伺服器回呼，公開 https）、`ECPAY_CLIENT_BACK_URL`（如 `https://<站>/shop/orders`，自動加 `?ecpay=<訂單ID>`）、`ECPAY_ACCEPT_SIMULATED_PAID`（預設 false）。`ECPAY_CHECKOUT_URL` 只允許綠界測試 AIO 網址，啟動時檢查；http 只允許 localhost。
- **建立**：`POST /customer/orders/{id}/ecpay-payments`（Cookie＋CSRF，`requestId`／`expectedOrderVersion`，冪等同 mock）。金額、MerchantTradeNo（`FG`＋18 碼）取伺服器快照，回傳表單欄位與 CheckMacValue，前端以表單 POST 到綠界。未完成的綠界付款在同一訂單重新付款時改為 FAILED（留 event），若之後才收到成功通知 → REVIEW_REQUIRED，不扣庫、不復活。
- **回呼**：`POST /payments/ecpay/callback`（免 Cookie／CSRF，只收 form、拒 query string 與重複欄位）。常數時間核對 CheckMacValue → MerchantID → 交易編號 → 金額，交給既有 `PaymentService.process` 核心（同交易扣庫、PAID、通知 outbox）。eventId = name-based UUID(MerchantTradeNo|TradeNo|RtnCode)，重送不重複扣庫。成功回 `1|OK`；驗證失敗回 `0|…`（綠界會重送）。`SimulatePaid=1` 預設只回 `1|OK` 不變更。ClientBackURL 只是前端頁面，**不改任何狀態**（避免 camp 專案未驗簽 return 被當成付款成功的漏洞）。
- 退款仍為模擬，不會呼叫綠界退刷；ATM／超商等非同步付款未做。
- **測試**：`EcpayGatewayTest`（官方文件 CheckMacValue 範例比對、設定檢查）、`EcpayIntegrationTest` 5 項（表單／重送、簽章成功只扣一次、偽造／竄改／金額不符／錯商店／未知編號／query／SimulatePaid 都不變更、mock 回呼不能結算綠界付款、重新付款與晚到成功待核對）。Flyway 計數 19→20。
- **上線待辦**：公開沙盒必須讓綠界伺服器能 POST 到 ReturnURL；Cloudflare 若擋（如先前擋 Python UA），需為 `/figura/api/payments/ecpay/callback` 加例外。本機無法收到綠界回呼，只能用簽章測試驗證。

## 2026-11-17：顧客手機＋Email＋密碼註冊、Email 驗證、忘記密碼（V19，含 schema 變更）

> 歷史紀錄：本段提到的舊帳號名稱註冊／登入與 `demo_customer` 帳號名稱登入，已被上方「會員架構定案」取代（只剩手機＋Email）。

分支 `feat/customer-email-phone`。**有 schema 變更（`src/main/resources/db/demo-migration/V19__…`，CD 受保護路徑），PR 不可直接 merge，需作者在伺服器端受控切換。** V1–V18 未改；V19 只新增可 NULL 欄位與新表，舊版程式可在新 schema 上運作。

- **V19**：`customer` 新增 `email`（ASCII、小寫正規化、UNIQUE）、`phone`（09xxxxxxxx、UNIQUE）、`email_verified_at`；新表 `customer_email_token`（一次性連結，只存 SHA-256、用途 VERIFY／RESET、到期與使用時間）、`customer_mock_email`（模擬寄信）。不寫入任何資料列，不改權限資料。
- **註冊** `POST /api/customer/auth/register`：新版 `{phone,email,password}`（不需暱稱；內部自動產生 `m_…` username，暱稱預設「會員＋手機末四碼」）。舊版 `{username,password,displayName}` 仍可用（Flutter App 相容），兩種不可混用。Email／手機各自重複回 409「此 Email 已被註冊」／「此手機號碼已被註冊」（大小寫、+886／空白／連字號寫法視為同一個；並發由唯一鍵保證只有一筆）。
- **登入**：`username` 欄位可填 Email、手機或舊帳號名稱；失敗一律 401「帳號或密碼不正確」，不揭露帳號是否存在。網站與 App 登入相同。
- **Email 驗證**：新版註冊後寄驗證信（24 小時）；`POST /api/customer/auth/email/verify {token}`（公開）；`POST /api/customer/email/verification`（本人重寄，60 秒一次；新連結使舊連結失效）。手機不驗證（展示用途）。目前不因未驗證阻擋下單。
- **忘記密碼**：`POST /api/customer/auth/password/forgot {email}` 一律 200（不揭露信箱是否註冊；同一信箱每小時最多 3 封）；`POST /api/customer/auth/password/reset {token,password}`（30 分鐘、一次性），成功後撤銷該顧客所有網站與 App 登入。
- **模擬寄信**：`MOCK_EMAIL_ENABLED=true` 時寫入 `customer_mock_email`，不實際寄出；預設 false（不寫入，連結不外流）。員工以 `GET /api/admin/notifications/mock-emails` 讀取，沿用既有「通知詳情」權限（`/api/admin/notifications/*` GET），未新增權限資料。接真實寄信服務時只需替換 `CustomerEmailService.deliver()`。
- **回應欄位**：`CustomerPayloads.View` 新增 `email`、`phone`、`emailVerified`（舊帳號為 null／false）。
- **測試**：新增 `CustomerEmailPhoneIntegrationTest`（7 項：正規化與重複、並發同 Email、Email／手機登入與一致失敗訊息、驗證一次性與重寄節流、忘記／重設與撤銷登入、忘記密碼節流、模擬信件僅員工可讀）。`CustomerIdentityIntegrationTest` 回應欄位斷言更新；7 個測試的 Flyway 版本數 18→19。
- **驗證結果**：隔離全新 DB（獨立 compose project、不同 port，測後刪除）`SHOP_MYSQL_INTEGRATION=true mvn package`：271 項，新增與顧客相關全部通過；**8 項失敗為既有問題**（`NotificationIntegrationTest` 7 項、`PreorderOrderIntegrationTest.notificationSafeSnapshots…` 1 項，mock 派送回 404），已在未修改的 main 上以同一環境重現相同 8 項，與本次無關，未修。本機 V19 套用成功，瀏覽器端到端：註冊、重複 Email／手機、手機登入、模擬驗證信與連結一次性、忘記密碼（不存在信箱不寫信）、重設後舊登入失效與新密碼登入。
- **伺服器端需要**：受控切換套用 V19；沙盒／正式站若要展示驗證信與重設密碼，需在私有環境設 `MOCK_EMAIL_ENABLED=true`；沙盒每日重置的 `demo_customer` 可維持帳號名稱登入（**2026-11-18 更正**：contact-only-login 上線後帳號名稱不能登入，每日重置種子必須替 `demo_customer` 設定 Email `demo-customer@example.com`，見最上方段落）。
- **未完成**：Facebook／LINE 登入（前端按鈕先顯示、無動作，待作者申請金鑰）；真實寄信服務；`NotificationIntegrationTest` 既有失敗。
- **Codex 審查後修正（同日）**：
  - 登入與重設密碼競爭：網站／App 登入寫入 session 後重讀密碼雜湊，已變更即撤回該 session 並 401。
  - 純數字舊帳號：手機查不到時退回帳號名稱登入；舊版註冊拒絕手機格式帳號（400）。
  - 註冊與驗證信同一交易（`CustomerRegistrationService`，網站與 App 共用），寄信資料寫入失敗整筆回滾。
  - 驗證／重設連結改為 `#token=`（片段不送伺服器、不進 Referer）。
  - 新增測試 4 項：連結過期、重設後 App access／refresh 與網站 refresh 皆失效、純數字舊帳號、寄信失敗回滾。隔離 DB 全套 275 項：新增 11 項全過，失敗僅既有 8 項。
  - **第二輪（Codex 複查）**：重設密碼在交易提交後再撤銷一次所有登入（關閉「登入重讀時重設尚未提交」的競爭）；新註冊手機若等於既有純數字帳號名稱也視為已使用；新增測試：手機與舊帳號名稱衝突、同一連結並發只能成功一次、三個公開 POST 缺 CSRF 回 403。全套 277 項，新增 13 項全過，失敗僅既有 8 項。
  - **未處理（接受風險）**：註冊分別回「Email／手機已被註冊」可被用來查詢是否為會員，這是作者要求的行為；目前沒有可靠的來源 IP（經 Cloudflare／Caddy）可做註冊節流，未加。

---

## 2026-11-08：自動更新端到端驗證（ELPSY Shop（API））

本次只增加此交接段落；不變更應用邏輯、資料庫、帳號、映像 recipe、CI 或部署契約。

- 目的：驗證 Atlas／Encore CD 恢復與主機讀取憑證換新後，正常 PR → 原完整 CI → merge → 私人 GHCR 發布 → Ubuntu 主機自然排程部署的完整鏈仍可用。
- 無可見內容變更；以主機實際部署來源 SHA 等於本次 main 作為判定。
- 主機私人部署完成後，再確認 Funnel 公開唯讀版自動跟隨新部署版本重建。
- 未完成上述實測前，不以本段文件或映像發布宣稱自動更新 PASS；結果保存於 server-management 私人交接。

---

## 2026-10-20：Shop 首次主機 CD PASS／配對自然更新驗證

- 測試標記：SHOP-CD-VERIFY-20261020-A。本次只新增交接段落，保留完整歷史，不修改業務、schema、帳號、資料、前後端 API 或發布 recipe。
- 已完成：主機 root shop-first-1792508954444032339／SHA256 02b32c967de74fcf2368fc3e48c0b67d43ca884b4db15a4654bc4d9e8828a3fd FIRST_CD_COMPLETE/PASS；原 Web6f2bc38c／API3ba73a1a 已成對部署，actual OCI／來源與兩個固定 digest 一致。
- 資料保護：fresh 加密備份/check/NAS copy、MySQL45表完整隔離還原、Redis/Seaweed bytes、真 Web/API old→new 與故障回舊全 PASS；正式 DB/Volume 不回灌、不 clean/seed。其他四站252探測0失敗，實際版本與掛載保留，正式資料未初始化。
- 本輪實測：主機匿名瀏覽器手機/桌面8項 PASS；主機 CD 工具114項離線及回歸 PASS。原來源 Hosted CI/Private 發布已成功，沒有重新執行。這些不冒充下一個來源的自然更新結果或完整商務E2E。
- 重要範圍：首次 CD 不等於 AUTO-CD；這次 Web/API 兩repo各走正常 docs PR、原完整 CI 與 Private GHCR，合併後須等兩者新main發布都就緒，才由既有 Ubuntu timer 配對更新。尚未通過的此 head/main CI、私有新digest、自然交易與actual OCI不得先標 PASS。
- 下一步：兩個 PR 原 CI 全 PASS 後再相鄰正常合併，不 force 或跳檢查；驗 main→CI→GHCR→自然 CD→新root metadata＋完成journal/資料指紋/其他四不變。完整 Shop AUTO PASS 後接續Encore。
- 保留：MySQL/Redis/Seaweed、網站Cookie/CSRF、App /ELPSY/api 別名及原資料契約，Flutter App不在本次；沒有真金流/物流/通知、Funnel、網路/SSH/UFW/Tailscale/NAS修改或新Token。完整主機證據見 server-management，不將舊來源歷史改成目前版本。


更新：2026-10-08。顧客App完成Android模擬器原生核心驗收與可安裝測試APK，準備iOS與商家簽章；仍共用本Spring/MyBatis後端與V18資料庫，不複製業務服務。現貨／整筆預購網站MVP及原生Bearer第一版已實作，付款／配送／退款／通知仍mock，尚未正式部署或商店上架。

起始本輪main：92213e2ca8332ebfe7bbe50774b4ce5bcaa2badb；App a0418a1ed31b3614808ee87f0841003a144f3d21；Vue 1e282a8a713657e684523cec4ab09d23ed4df9c5。先fetch／讀遠端HANDOFF與AGENTS／status/log/diff／ff-only。本輪只涉及ELPSY三專案。

## 已有後端與重要檔案

/api/mobile/customer JSON Bearer realm/session 與網站Cookie/CSRF、員工realm隔離；owner只CustomerPrincipal，15分鐘access／7天refresh原子輪替、DB即時啟用核對、Redis2秒timeout，native aliases共用原cart/address/現貨／預購服務。無新增migration，V1–V18不可改。重要檔案 CustomerSessionService、MobileAuthController／MobileAuthenticationFilter、SecurityConfig、MobileIdentityIntegrationTest、scripts/smoke_mobile.py、docs/MOBILE_API.md。

第一版詳細實作與回歸見[2026-10-07歷史交接](verification/customer-app-handoff-2026-10-07.md)；V18詳細見[預購履約歷史交接](verification/preorder-fulfillment-handoff-2026-10-07.md)。

## 本輪驗證與限制

Android29 模擬器使用真 NativeStore／AndroidKeyStore 與本後端，核心4階段通過（冷啟動與輪替、已提交回覆遺失的原 UUID/body 重試同一單、ACK後GET503、版本寫入只讀核對與owner隔離）。最終產品入口 debug APK 0.1.1+2 的手動 UI／生命週期8項通過。完整 UI 自動化被 System UI ANR／UiAutomator45秒逾時阻擋；Skia 背景返回黑畫面未通過，預設renderer短暫黑畫面後恢復。實體手機、硬體儲存及效能仍未驗收。

本輪後端完整 mvn package **262項：260通過、2既有fixture skip、0failure/error**，原生真HTTP **19通過**，DB **39項invariant全0**、history18，V1–V18 bytes不變；Vue production build（vue-tsc／Vite）成功。後端及Vue本輪沒有產品程式變更。前輪 Dart live1、browser11、網站HTTP153及API故障10屬歷史證據，本輪未重跑，不能寫成裝置測試結果。

安全證據：[customer-app-device-2026-10-08.json](verification/customer-app-device-2026-10-08.json)。詳[App本輪交接](https://github.com/jackteng-dev/Shop_app/blob/main/docs/HANDOFF.md)、[USB安裝與iOS／正式簽章步驟](https://github.com/jackteng-dev/Shop_app/blob/main/docs/DEVICE_ACCEPTANCE.md)。

## 下一步與保存

Android 測試APK在私人 Shop_app 草稿版本 tag v0.1.1-device-test；只連 localhost9090，需使用者電腦自己的 demo 與 USB adb reverse，不是正式部署。正式Android商家簽章條件設定及iOS own-app Keychain entitlement已準備；完整設定以拋棄式開發key驗過，不是商家正式簽章。iOS只有靜態plist／設定檢查，Linux未做Xcode建置。

下一步實體Android預設renderer／Keystore／背景／斷網／更新驗收，以及Mac/Xcode、Apple Team、HTTPS API下的iOS／Keychain驗證。正式部署、簽章／平台帳號、真provider、會員安全及營運另列待驗；整筆預購MVP已完成，訂金尾款／延期／部分退款仍選擇性擴充。

只用全新自有 elpsy-app-device-20261008 scope；封存自己1商品／關閉1批次，停止自有API／controller／模擬器，移除3容器／2 volumes／network，核對23306／27379／28333／29092／29102關閉。未改原DB、其他作品或寄真人訊息。私人fixture／SDK／日誌／截圖不進Git。三倉庫各自main提交／推送，不force，核對實際遠端與乾淨status。

交付補充：原雲端APK上傳端點401，改以手動GitHub Actions建置成功並附到私人草稿。[下載](https://github.com/jackteng-dev/Shop_app/releases/tag/untagged-2e7d99bc1f7c451ca04d)／[建置](https://github.com/jackteng-dev/Shop_app/actions/runs/37712548885)；實際交付SHA256 `4ac11c22115f0c073b91247f8fe1e42e26aef18fa1a07b0bc447f320899f06d1`，180297725 bytes，下載雜湊／開發簽章／code2／3 ABIs已核對。原生核心及手动UI證據屬原雲端APK；CI重新建置檔尚未跑裝置驗收，簽章亦不同，不能合併宣稱同檔驗證。
