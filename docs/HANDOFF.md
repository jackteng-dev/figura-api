# Figura（原 ELPSY Shop） 後端交接

## 2026-11-25：Commerce V2 Phase 1–7（V25–V30，未合併，需受控切換）

分支 `feat/commerce-v2-specs`（Web／App 同名）。計畫、完成狀態與待啟用清單：`docs/COMMERCE_V2_PLAN.md` §6–7。契約：`CATALOG_SEARCH_API`、`ENGAGEMENT_API`、`WAREHOUSE_API`、`SUPPORT_API`、`OPS_SECURITY`，Web `docs/SEO_DEPLOYMENT.md`。**V25–V30 位於受保護路徑，需作者受控切換；不 merge、不部署、未碰正式 DB。** V1–V24 bytes 不變。

- **P1 規格（V25）／P2 搜尋（V26）**：規格欄位與白名單；既有 99 款不填規格；AI 概念材質公開不出現在 materials。ngram 搜尋、facets、排序白名單（含 STOCK_FIRST）；可下單批次規則（開放、未截止、有配額、款式啟用）由搜尋、商品摘要、提醒掃描共用；主檔改名同交易重建 search_text。
- **P3（V27）**：收藏（含訪客合併）、商品提醒（`ProductAlertScanner` 狀態轉換才通知、首次只建基準、source_id 防重）、通知偏好、站內通知匣（outbox 增 product parent 與 read_at）；`EmailSender`＋`MockEmailSender`。
- **P4 SEO**：`/api/seo/head|body|meta|sitemap.xml|robots.txt|guides`、`GuideCatalog`（只寫系統實際規則與一般知識）；無評價、offers 預設關閉、AI 概念商品不輸出 Product；已發布政策頁才索引。
- **P5（V28）**：出貨單合併（只搬 shipment_item，運費只減不增，差額 `shipping_adjustment` 待審）、私人倉庫暫存（暫存中不可出貨，單筆端點也擋）、到期提醒、員工解除／延長、運費調整審核（CREDIT_MOCK 只改狀態）。
- **P6（V29）**：政策頁草稿→送審→**發布者不可是最後編輯者**；8 個空白草稿，未發布公開端點不回草稿（標題用固定名稱）。客服單（內部備註不外露、關聯訂單須本人）、測試物流追蹤與到店／異常通知、`CarrierAdapter` 接入點。
- **P7（V30）**：員工 TOTP（自願、AES-GCM 加密、challenge 原子扣次數與一次性消耗、帳號層級節流、失敗稽核）、銷售報表（模擬／綠界測試／真實分開，真實營收 0）、低庫存、營運狀態、未處理例外錯誤代碼與 ERROR 日誌（原本 500 不記錄）。
- **測試**：新增 ProductSpecs 4、CatalogSearch 2、Engagement 3、Seo 2、Warehouse 2、Support 3、Ops 2。全套 **342 項：0 failures、2 errors（`StaffAccessIntegrationTest`，本機 DB 角色數既有問題）**。本機測試環境需 `MOCK_NOTIFICATIONS_ENABLED=true` 與 Hikari 上限（`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=4`、`MINIMUM_IDLE=0`，已寫入忽略的 `work/test-env.sh`），否則通知測試 404、多個 context 會 Too many connections。測試 DB 先前失敗殘留的 34 筆 1990 年 outbox 合成資料已延後到 2099，才讓 worker 測試通過。
- **Codex 唯讀審查**：P1–2 六項、P3–7 六項（MFA 併發繞過 P1、MFA setup 競態、提醒與客服回覆死鎖、草稿標題外洩、失敗稽核回滾）全部修正並加測試；覆審再指出帳號層級節流可被併發繞過，已改為 Redis 原子先保留額度再核對並加測試。
- **本機 demo**：DB 已到 V30，API 以新 jar 執行於 19092；經 Web 代理驗證收藏／提醒／通知、報表（真實營收 0）、低庫存、營運狀態、政策頁未發布、員工登入（未啟用 MFA）不變。本機未設 `MFA_ENCRYPTION_KEY`，MFA 顯示不可用屬預期。
- **未完成／限制**：正式金流、Email、物流 API、Search Console、nginx SSI（部署契約）、政策內容、倉庫與運費政策、備份排程皆列待啟用；新後台頁未以員工帳號在瀏覽器實際點過；App 未做真機；`StaffAccessIntegrationTest` 2 項為本機 DB 角色數既有問題。
- **下一步**：作者審核 PR → 隔離環境演練 V25–V30 → 受控切換 → 合併 API，再合併 Web／App；審核 gateway SSI。

## 2026-11-19：商品多圖與 V24（未合併／受控切換前不可 merge）

分支 `codex/product-gallery`，基於遠端 main `6121c3a`；配對 Web `codex/comfyui-product-gallery`。**V24 位於 CD 受保護路徑 `src/main/resources/`，需要作者在伺服器執行受控 schema／contract switch；本輪不 merge、不部署、不操作正式 DB。** V1–V23 bytes 不變。

- 新 `ProductGalleryService`／`GalleryMapper`／`GalleryPayloads` 與 V24 四張 additive 表（gallery、variant、command、gallery_event）。最多六張，用途、完整排序、獨立主圖、替換／移除；舊 cover lazy materialize，Catalog／App 的 image 五欄與單主圖 API 保持相容。新舊 audit 都可讀，沒有刪改歷史 API。
- 寫入 actor live grant → product → 全域 UUID reservation → cleanup queue；商品版本、主圖、圖組、衍生 metadata 與 audit 同 SQL transaction。新請求必帶 `expectedActorId`，只作 Principal 的交易內身分前置核對，audit actor 仍只取 Principal。PNG／JPEG 來源 bytes 重編碼；S3 PUT 前持久 orphan reservation、清理包含所有 active gallery／variant 引用，故障／回滾不會刪 active 圖。
- JDK ImageIO 衍生 320／640／1024 寬圖片，不拉伸、不放大；私有 SeaweedFS、不可變 key、來源與衍生 bytes／hash 核對、nosniff／no-store。舊單圖沒有 variants 時仍讀原圖；移除主圖（含 legacy API）遞補排序第一張。
- 公開 GET `/catalog/products/{id}/image` 只有已上架有效商品圖組；原 `.../image/{imageId}` 增 width 選項。員工沿用固定權限路徑／Cookie／CSRF，不新增任意 URL 授權。JSON REMOVE 用 DELETE 保持獨立 remove grant。完整合約及回退限制見 `docs/PRODUCT_GALLERY_API.md`。
- **測試／build**：全新 `figura-gallery-20261119` Docker project＋空 figura_shop_demo／獨立 Redis／SeaweedFS，Java 21 完整 `mvn package` **324 項：322 通過、2 舊 migration fixture opt-in skip、0 failures／errors**。保留原單圖測試，新增五項 gallery 測試涵蓋 publication／舊版相容／尺寸／上限／排序／主圖／替換／全球 request ID／併發／CSRF／即時 grant／actor precondition／稽核回滾／active cleanup。V24 實際套用，未更動既有 DB。
- 本機多個 Spring test contexts 曾耗盡預設 MySQL 連線，僅測試私有環境設 Hikari max=5／minIdle=0 後重跑成功。兩個缺省略過的是 `SHOP_MIGRATION_FIXTURE_SKU` 與 `SHOP_V5_FIXTURE_CUSTOMER` 的既有升級 fixture；Redis 兩項已啟用並通過。
- Maven 預設 target JAR 被原本本機 API 鎖住，首輪 repackage 失敗；後改 ignored `work-gallery.pom.xml`（只改 build.directory，來源／依賴／測試不變）輸出到 `work/gallery-target`，完整 package 成功。已由 origin/main 的隔離建置還原原 `target` 可執行 JAR，暫用 worktree 已移除，既有 API HTTP 200。沒有重啟或升級原有 demo API／DB。
- **Web／App**：Web production build、46 項真瀏覽器本機整合通過；兩款各四張主圖裁切候選經實際 API 儲存與顧客詳情展示；新側面／情境有漂移全部退回，作者尚未 signoff。App 未改，遠端 main analyze／27 tests＋2 skip／Web release build 通過，原 image 路徑相容；不宣稱 Android／iOS 裝置完成。
- **Codex 唯讀 review**：提供完整 diff／新增程式作靜態審查，修正 actor 競爭、legacy 主圖遞補與 Web pipeline 兩項中斷恢復，共四項 P2；覆審無 P0／P1／P2。CLI 原 inspection tool 無法啟動的限制已如實記錄，審查不冒充測試。
- 證據：`docs/verification/product-gallery-2026-11-19.json`。Raw logs／密碼／測試產物在忽略的 work。本輪 WebP／AVIF 只在 ComfyUI 本機匯出／比較頁，API 仍 PNG／JPEG；沒有加入伺服器外部編碼器。正式 CWV、真機、正式圖片採用及 schema switch 未做。
- 下一步：作者簽核圖組；安排受控切換與回退演練，先暫停圖片寫入／舊 cleanup（舊程式不知道 gallery／variant 引用）。PR 保留未合併狀態；不得自動 merge 或以一般 CD 直接套 V24。



## 2026-11-18：Phase 5 配送：運費、宅配／超商、合併／分開出貨、出貨單（V23，含 schema 變更）

分支 `feat/shipping`（疊在 `feat/preorder-deposit` 上）。**新增 `V23__shipping_methods_and_shipments.sql`（受保護路徑），合併前需作者受控切換**；需先完成 V21、V22。V1–V22 未改。

- **V23**：放寬 `customer_order.ck_order_totals`／`preorder_order.ck_preorder_order_amount` 的 `shipping_twd=0` 為 0–100000（新 migration 改 CHECK，不改舊檔）；`shipping_method`（HOME 宅配到府、CVS 超商取貨；基本運費、免運門檻、開放與否、版本）＋`shipping_method_event` 稽核；`shipment`（目的地快照、運費、商品金額、測試物流 carrier=TEST、`MOCK-` 貨件號）、`shipment_item`（每張訂單只屬一張出貨單）、`shipment_event`（整張出貨／送達稽核）；`checkout_group` 記錄配送方式／策略／運費；權限 258–263（出貨單讀取／詳情／出貨／送達、運費讀取／修改；一般員工只有讀取）。**預設費率是測試值（宅配 120／滿 3000 免運、超商 60／滿 1500 免運），上線前需作者核定**。
- **結帳**：`GET /customer/checkout/quote?shippingMethod=HOME|CVS&deliveryStrategy=SPLIT|COMBINED`；`GET /customer/checkout/pickup-stores`（5 間**測試門市**，不是真實超商資料）；`POST /customer/checkout` 新增 `shippingMethod`、`deliveryStrategy`、`pickupStoreId`（省略＝宅配分開寄；舊客戶端未含運費的金額會 409 要求重新核對，不會默默多收）。SPLIT 每組一張出貨單、各自計運費；COMBINED 全部一張出貨單、只算一次運費（記在現貨單，沒有現貨時記在第一張預購單）。運費依每個包裹的商品金額判斷免運，記在承運訂單的 `shipping_twd`，所以付款、退款金額自動含運費；訂金預購的運費加在尾款。直接購買、直接預購維持原行為（免運、沒有出貨單）。
- **出貨單**：狀態由組成訂單推導（WAITING 付款／尾款／到貨／待核對、READY、SHIPPED、DELIVERED、CANCELLED），`/admin/shipments`、`/admin/shipments/{id}`、`POST .../ship`、`.../deliver`（需 `MOCK_FULFILLMENT_ENABLED`）整張出貨或送達，同時寫入每張訂單既有的履約紀錄、版本、批次出貨數與通知；同 requestId 重送回原結果、內容不同 409。合併出貨單內的訂單不能用舊的單筆出貨端點（避免一半寄出）；單一訂單的出貨單兩種端點都可用。訂單詳情（現貨、預購）附上 `shipment`。
- **運費規則**：`GET/PUT /admin/shipping-methods/{code}`（版本、原因、稽核、同 requestId 重送）；停用的配送方式報價 `ready=false`、下單 409。
- 鎖序：出貨單操作 actor/grant → 顧客 → 現貨訂單 → 預購批次（排序）→ 預購訂單（排序）→ 出貨單，與付款、單筆履約、時程／尾款通知一致。
- 測試：`ShippingIntegrationTest` 4 項（各方式／策略運費與免運門檻、超商合併出貨等預購到貨後整張出貨與送達、分開寄各訂單運費與訂金尾款含運費、單筆出貨相容、舊客戶端金額、運費規則稽核／版本／權限／停用）。`UnifiedCheckoutIntegrationTest` 金額改為含預設運費。全套 `mvn test`：313 項，只剩既有 8 項本機通知失敗（測試 context 屬性順序與 Phase 4 測試一致以共用連線）。本機瀏覽器（隔離 DB）：結帳切換宅配／超商、分開／一起寄，運費 240／120／0 正確；超商合併下單 → 兩張付款 → 等預購到貨 → 到貨分配 → 整張出貨 → 訂單頁顯示測試貨件號、訂單中心兩張皆已出貨。
- 未完成／待確認：真實物流／超商 API、真實門市選擇、運費正式費率、部分出貨與退貨寄回運費，都需實際服務與作者核定（見 CLOUD_LOCAL_FOLLOWUP）。員工後台頁面未在瀏覽器實際操作（員工登入被本機權限設定擋下），以 API 測試與型別檢查涵蓋。

## 2026-11-18：Phase 4 預購訂金／尾款、延期與通知（V22，含 schema 變更）

分支 `feat/preorder-deposit`（疊在 `feat/unified-cart` 上）。**新增 `V22__preorder_deposits_and_schedule.sql`（受保護路徑），合併前需作者受控切換**；需先完成 V21。V1–V21 未改。

- **V22**：`preorder_batch` 加 `payment_mode`（FULL／DEPOSIT）、`deposit_twd`（每件訂金，需小於單價）、`balance_days`（1–60）、`balance_called_at`；`preorder_order` 加付款條件快照（`deposit_twd`、`balance_twd`、`balance_status` NONE／NOT_DUE／DUE／PAID、`balance_due_at`、`balance_paid_at`），CHECK 保證訂金＋尾款＝總額；`preorder_mock_payment.stage`（FULL／DEPOSIT／BALANCE）；新表 `preorder_schedule_event`（延期／調整／尾款通知的不可變稽核，`public_reason` 給顧客、`note` 只給員工）；權限 256 `preorders.schedule`、257 `preorders.balance.call`（只給管理員角色）。既有資料經預設值成為 FULL，原付款／退款紀錄不動。
- **付款**：訂單 `status=PAID` 對訂金訂單代表「訂金已付、名額已確保」（held→paid 只發生一次）；尾款另由 `balance_status` 追蹤，尾款付款不動名額。付款金額一律由伺服器決定（`payNowTwd`）。晚到／重複通知：尾款付款遇到已退款或非待付尾款 → REVIEW_REQUIRED（保留證據、不復活）。
- **尾款通知**：`POST /admin/preorders/{id}/balance-call`（需先登記到貨）把已付訂金、未通知的訂單改為 DUE，期限＝現在＋`balance_days`，並排入 `PREORDER_BALANCE_DUE` 通知；之後才付訂金的訂單自動進入 DUE。逾期不自動沒收或取消（正式政策待作者核定），訂單中心把待付尾款列在「待付款」。
- **延期／調整**：`POST /admin/preorders/{id}/schedule` 更新批次與尚未到貨、未結束訂單的預計到貨區間，記錄前後日期與顧客說明，通知 `PREORDER_DELAYED`（延後）或 `PREORDER_RESCHEDULED`（提前／調整）。顧客訂單 `schedule` 只列下單後的異動、只含對外說明。
- **鎖序**：時程／尾款通知為 actor/grant → 受影響顧客（依 ID）→ batch → 訂單（依 ID），不取 master guard；與下單（customer → master → product → batch）、付款／取消／退款（customer → batch → order）一致，避免 outbox 外鍵造成的 customer 鎖反向等待。名單在鎖批次後若出現未鎖的顧客，回 409 要求重讀。
- **分配與退款**：訂金訂單需尾款已付才可分配到貨；整筆退款（未到貨）會對每一筆成功付款各寫一筆退款稽核（訂金、尾款），訂單版本依筆數遞增，顧客看到退款合計。
- **相容**：批次寫入未送付款條件時 fingerprint 與 V22 前相同（升級前未確認的原操作仍可原 requestId 核對）。統一結帳報價依批次條件回 `payNowTwd`／`payLaterTwd`。
- 測試：`PreorderDepositIntegrationTest` 5 項（訂金→到貨→尾款通知→尾款→分配、後付訂金自動待付尾款、延期／調整紀錄與通知且不洩漏內部說明、只付訂金退款與訂金＋尾款兩筆退款、條件驗證與全額不變、統一結帳報價、尾款通知與付款並發無死鎖）。全套 `mvn test`：308＋1 項，只剩既有 8 項本機通知失敗。本機瀏覽器實測（隔離 DB）：商品頁顯示付款方式、結帳顯示先付訂金／之後尾款、付訂金、延期紀錄、尾款通知後訂單中心顯示待付尾款、付尾款。
- 未完成／待確認：尾款逾期處理政策（目前只顯示期限）、部分到貨或部分退款仍不支援；員工後台頁面未在瀏覽器實際操作（登入員工帳號被本機權限設定擋下），以 API 測試與型別檢查涵蓋。

## 2026-11-18：Phase 3 統一購物車與結帳（V21，含 schema 變更）

分支 `feat/unified-cart`（疊在 `feat/order-center` 上）。**新增 `V21__unified_cart_and_checkout_groups.sql`，屬受保護路徑 `src/main/resources/`：合併前需作者在伺服器端做受控切換（contract switch）**；V1–V20 未改。

- **V21**：`customer_cart_preorder`（本人購物車的預購項目，以批次為鍵、1–100 件，FK 到購物車與批次）；`checkout_group`（一次結帳，`UNIQUE(customer_id,request_id)`＋內容 fingerprint）；`checkout_group_order`（每張子單一列，現貨／預購恰一，訂單 ID 唯一）。只新增表，不改既有欄位。
- **購物車**：`PUT/DELETE /customer/cart/preorders/{batchId}`（及 `/mobile/...`），核對整車版本；批次需 OPEN、未截止、商品上架且 SKU 啟用、數量 ≤ 可用配額；**不保留配額**。現貨＋預購合計最多 50 個品項。`CartView` 新增 `preorders`、`preorderSubtotalTwd`、`totalTwd`、`checkoutReady`（原 `items`／`subtotalTwd`／`ready` 只計現貨，舊客戶端相容）。
- **結帳**：`GET /customer/checkout/quote` 回伺服器分組（現貨一組；每個預購批次一組，含到貨區間／月份、截單、payNow／payLater）；`POST /customer/checkout`（`requestId`、`cartVersion`、`addressId`、`addressVersion`、`expectedTotalTwd`）**單一交易**：customer 鎖 → master guard → 依 ID 排序鎖商品 → 重讀購物車核對總額 → 建立現貨子單（重用 `OrderService.createForCheckout`，MANDATORY）→ 依批次 ID 排序建立預購子單（重用 `PreorderOrderService.place`）→ 清空購物車（現貨與預購）→ 版本＋1。任何一張失敗（配額不足、價格變動、版本不符）**全部回滾**，購物車保留。子單 requestId 由主 requestId 決定性衍生；同 requestId 同內容重送回目前狀態（`replayed=true`）且不再保留，不同內容 409。
- **相容**：舊 `POST /customer/orders`（購物車只結現貨）照舊運作，且不動預購項目；`/orders/direct`（直接購買）、`/preorders`（直接預購）不變。
- 測試：`UnifiedCheckoutIntegrationTest` 6 項（混合拆單與冪等、任一預購失敗連現貨一起回滾、價格／版本／欄位／他人地址／未登入、批次狀態與配額與版本、舊端點相容、並發搶最後配額只有一方成功與純預購購物車）。各 Flyway 筆數斷言改為比對 classpath 上的 migration 檔數（之後新增 migration 不必再改測試）。隔離 DB 全套 `mvn test`：304 項，失敗只剩既有 8 項通知相關（本機 `MOCK_NOTIFICATIONS_ENABLED` 與非空 DB；CI 用全新 DB）。本機 demo 啟動自動套用 V21；網站與 App 實機下單（混合 3 張、純預購 1 張）通過。
- 待辦（非阻斷）：舊會員清理包（`work/cleanup-20261118/`）如需再執行，要納入 `checkout_group*` 與 `customer_cart_preorder`。

## 2026-11-18：Phase 2 統一訂單中心（唯讀 API）

分支 `feat/order-center`（疊在 `feat/buy-now` 上）。**無 schema、未改受保護路徑**。

- `GET /customer/order-center`、`/mobile/customer/order-center`（網站 Cookie 與 App Bearer 共用路由允許清單），參數 `kind=ALL|STOCK|PREORDER`、`stage=ALL|PENDING_PAYMENT|WAITING_ARRIVAL|TO_SHIP|SHIPPED|COMPLETED|RETURNING|CANCELLED|REFUNDED|REVIEW`、`sort=NEWEST|OLDEST|AMOUNT_DESC|AMOUNT_ASC`、`page`、`pageSize≤50`；回傳摘要列表、`total`、各階段 `counts`。詳情與操作仍用原本 `/customer/orders/{id}`、`/customer/preorders/{id}`。
- `OrderCenterService`：一個 `UNION ALL` 合併本人現貨與預購，在 DB 內篩選、排序、`LIMIT/OFFSET`，所以分頁與總數是跨兩種訂單的全域結果；現貨明細以本人範圍的彙總 join 取得。stage 由原始狀態推導、不改任何資料；**未處理的付款核對最優先**（取消／逾時後晚到的成功通知會出現在「付款待核對」）。owner 只取 Principal，參數用 enum 白名單。
- 測試：新增共用 `ShopIntegrationSupport`（之後各階段共用）與 `OrderCenterIntegrationTest`（3 項：真實付款／出貨／取消／預購付款產生六種狀態，驗證 stage、篩選、金額與時間排序、跨類型分頁、counts、本人隔離、401／400、App Bearer 與 Cookie 隔離、取消後晚到付款進入核對）。
- 待辦（非阻斷）：大量訂單時的 `EXPLAIN` 效能驗證；核對退款後回到原階段的整合測試。

## 2026-11-18：會員架構定案（正式電商方向）

同分支 `feat/contact-only-login`，無 schema 變更、未改 `src/main/resources/`。作者決定不再保留練習版的暱稱、帳號名稱登入與舊註冊格式。

- **本人資料 `View`** 只回傳 `{id, createdAt, email, phone, emailVerified}`；不再回傳內部 `username`／`displayName`（Web／App 同步移除）。`customer.username`／`display_name` 欄位保留（NOT NULL，維持 schema 相容），由系統產生內部值，不能登入、不對外。
- **刪除無用程式**：舊會員 API `UserController`（`/api/user/*`，filter 早已回 410、不可達）；`CustomerPayloads.USERNAME`、`CustomerMapper.byUsername`、`CustomerSessionService.normalize`。`/api/user/*` 仍由 filter 回 410。員工端 `user` 表、`AdminUser*`、認證、Token、稽核未動。
- **沙盒示範會員**：`DemoCustomerSeeder`（demo profile，由 `DemoAccountInitializer` 啟動時呼叫）在提供私有 `DEMO_CUSTOMER_PASSWORD` 時建立 Email `demo-customer@example.com`、手機 `0900000000`、Email 已驗證的會員。已存在只核對手機／啟用／驗證（不符停止啟動）與密碼（不符只記錄 ERROR：AGENTS 不能重設既有用戶，也避免整個沙盒起不來；但這代表公開帳密失效，重置後登入驗收必須失敗）；不修改資料；未設定就不建立。建立與核對在獨立連線上用 MySQL 具名鎖序列化，並行時不會誤判。沙盒（demo profile 且有示範密碼）下：此 Email 的忘記密碼不發連結、既有重設連結無效（公開員工示範帳號能讀模擬信件，否則訪客可改掉公開密碼）；訪客不能註冊此 Email 或手機。正式環境（非 demo profile）即使有這個變數也不受影響。選用 `DEMO_CUSTOMER_SELF_HEAL=true` 每分鐘補建，只是安全網。新增 `DemoCustomerIntegrationTest`（9 項）。
- **伺服器端每日重置（不在任何 repo，需作者修改，尚未完成）**：
  1. 沙盒私有環境加 `DEMO_CUSTOMER_PASSWORD=figura-demo-customer`（與 README／App Release 說明一致）。
  2. 重置資料移除舊 `demo_customer`（只有帳號名稱）列；**不要**在重置資料裡放新版示範會員（交給 API 建立，密碼才會和環境變數一致）。
  3. 重置流程必須是「停 API → 重置資料庫 → 啟動 API」。示範會員與員工示範帳號 `shop_admin`／`shop_staff` 都只在 API 啟動時建立（員工帳號只在 `user` 表為空時建立），不重啟就不會補回。`DEMO_CUSTOMER_SELF_HEAL` 不能取代重啟：它不知道還原何時進行、不補員工帳號。
  4. 重置後驗收：API log 沒有「示範會員」ERROR；用 `demo-customer@example.com` 與 `0900000000` 各登入一次、用 `shop_admin` 登入一次；任何一項失敗都算重置失敗。
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
