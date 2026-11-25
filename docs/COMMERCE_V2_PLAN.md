# Figura 正式商城化（Commerce V2）實作計畫與契約

2026-11-25。範圍：在不接正式金流、第三方登入、3D 的前提下，把商品、搜尋、收藏通知、SEO/GEO、訂單合併與私人倉庫、客服政策、後台營運補到接近正式公仔商城。既有功能（會員、購物車、統一結帳、訂單中心、訂金尾款延期、配送拆單、商品多圖、ComfyUI 產圖）不重做，只在其上延伸。

## 0. 現況盤點（2026-11-25，三個 repo 的 main）

| 範圍 | 現況 | 本輪處理 |
|---|---|---|
| 商品資料 | `catalog_product`：title、brand/category/work 主檔 FK、description、status、version；SKU 只有 label/price/enabled | **缺**公仔規格欄位 → Phase 1 |
| AI 概念商品標示 | 只靠 description 開頭字串「AI 生成概念圖｜展示商品」與圖片 alt | Phase 1 改為明確欄位 `content_origin`，以既有字串回填，不改內容 |
| 搜尋篩選 | `GET /catalog/products` 只有 page/pageSize；Web `useCatalog.ts` 讀最多 20 頁後在瀏覽器篩選；App 只讀詳情 | **改後端** → Phase 2 |
| 收藏 | 無 | Phase 3 |
| 通知 | V14 outbox（order／preorder_order 恰一 parent）、MOCK 派送、Worker 預設關；Email 只有 `customer_mock_email` 模擬 | Phase 3 延伸 parent 與訂閱，不另建系統 |
| SEO | Vue SPA；正式 nginx `try_files … /index.html`，所有頁面同一份 head；根目錄 `/` 轉向員工後台 | Phase 4 |
| 訂單合併／私人倉庫 | 無；有 shipment（V23）可承載合併出貨 | Phase 5 |
| 客服／政策 | 無 | Phase 6 |
| 後台安全 | 員工 session、即時權限、多項稽核；無 MFA、無營運統計 | Phase 7 |

## 1. 原則

- **Contract first**：每階段先定 API（本文件＋各 `docs/*_API.md`），API 完成並測試後，Web、App 依同一契約實作；員工後台只做 Web。
- **受控 migration**：只新增 V25 起的 migration，V1–V24 不動。全部**只新增**（新表、可 NULL／有預設值的欄位、索引），舊版程式在新 schema 上仍可運作；含資料寫入（回填、權限）的 migration 在 PR 註明，由作者受控切換。
- **不杜撰**：既有 99 款 AI 概念商品的比例、尺寸、製造商等未知規格一律留空（NULL＝未提供）；AI 設定材質只以「概念設定」呈現，不放進真實製造規格或結構化資料的 material。
- **不冒充**：Email、物流、金流沒有正式金鑰時只做 Adapter＋Mock＋設定文件，列入待啟用清單。
- **既有流程不可壞**：模擬金流、統一結帳、App 購物流程維持可用，每階段跑全套回歸。
- **委派**：API 契約定稿後，Flutter 顧客端畫面、靜態指南內容骨架等可獨立工作交給本機 Codex（各自分支），Claude 負責架構、API、整合與審查。

## 2. 分階段與 migration

| 階段 | migration | 主要內容 |
|---|---|---|
| P1 商品規格 | V25 | `catalog_product` 規格欄位、`content_origin`、搜尋用 `search_text`；後台編輯；詳情頁規格表 |
| P2 搜尋篩選 | V26 | 篩選／排序索引、ngram FULLTEXT；`GET /catalog/products` 查詢參數（向下相容）；`/catalog/facets` |
| P3 收藏與通知 | V27 | `customer_favorite`、`customer_notification_subscription`、`notification_outbox` 增加 product／customer parent 與 channel；排程掃描 |
| P4 SEO/GEO | 無 schema | `/api/seo/*`（sitemap、商品 head、robots 來源）、nginx SSI（部署契約變更）、知識／指南頁 |
| P5 合併與私人倉庫 | V28 | `order_merge_request`、`warehouse_item`、暫存期限與提醒；沿用 shipment |
| P6 客服與政策 | V29 | `site_page`（草稿／待審／發布＋版本）、`support_ticket`／訊息；物流單號與進度沿用 shipment 並補追蹤事件 |
| P7 後台營運 | V30 | 員工 TOTP MFA（選擇啟用）、營運統計（區分模擬與真實付款）、低庫存提醒、備份還原文件 |

每階段：API＋測試 → Web → App → 全套測試、build、analyze → HANDOFF → commit。PR 依 repo 開，含 migration 者標示需受控切換，不自行合併。

## 3. Phase 1 契約：商品規格（V25）

### 欄位（全部可 NULL，NULL＝未提供）

| 欄位 | 型別 | 說明 |
|---|---|---|
| `character_name` | VARCHAR(120) | 角色名稱 |
| `manufacturer` | VARCHAR(80) | 製造商（與品牌不同時填） |
| `scale` | VARCHAR(16) | `1/4`、`1/6`、`1/7`、`1/8`、`1/12`、`NON_SCALE`（無比例） |
| `height_mm` | INT | 全高（含底座，mm） |
| `dimensions` | VARCHAR(160) | 其他尺寸說明（例：寬 120 × 深 100 mm） |
| `materials` | VARCHAR(160) | 逗號分隔代碼：PVC、ABS、RESIN、POLYSTONE、METAL、FABRIC、OTHER |
| `product_type` | VARCHAR(24) | PREPAINTED（完成品）、ACTION_FIGURE（可動）、MODEL_KIT（組裝模型）、GARAGE_KIT（GK）、PRIZE（景品）、BLIND_BOX（盒玩）、STATUE（雕像）、OTHER |
| `edition` | VARCHAR(16) | STANDARD（一般版）、LIMITED（限定版）、BONUS（特典版）、DELUXE（豪華版） |
| `is_limited` | BOOLEAN NOT NULL DEFAULT FALSE | 限定商品 |
| `accessories` | VARCHAR(1000) | 配件與附屬品（一行一項） |
| `release_date` | DATE | 預計發售日（精確日） |
| `release_month` | CHAR(7) | 預計發售月（`YYYY-MM`，只知道月份時用） |
| `spec_note` | VARCHAR(2000) | 規格補充說明 |
| `content_origin` | VARCHAR(16) NOT NULL DEFAULT 'REAL' | `REAL`（真實商品）／`AI_CONCEPT`（AI 虛構展示商品） |
| `concept_materials` | VARCHAR(160) | AI 概念商品的「設定材質」，只作設定描述，**不**進正式規格與 JSON-LD |
| `search_text` | VARCHAR(1000) | 伺服器維護的搜尋文字（標題、角色、作品、品牌、製造商），不接受用戶端寫入 |

回填：`content_origin='AI_CONCEPT'` 只針對 description 以「AI 生成概念圖｜展示商品」開頭者；其他欄位不回填（不猜）。`search_text` 由程式在啟動後首次寫入或 migration 以既有欄位組合。

預購開始／截止時間沿用 `preorder_batch`（opened_at、deadline_at），商品 API 只回傳目前開放批次的摘要，不重複存放。

### API（向下相容：舊欄位不變，新增欄位）

- `POST/PUT /admin/products`：`Write` 新增上述可選欄位；列舉白名單驗證；`release_date` 與 `release_month` 擇一；`height_mm` 1–5000；materials 只接受代碼。
- `GET /admin/products/{id}`、`GET /catalog/products/{id}`、列表：回傳 `specs` 物件與 `contentOrigin`。AI_CONCEPT 的 `specs.materials` 一律空，概念設定放 `conceptMaterials`。
- `GET /catalog/products/{id}` 另回 `availability`：`IN_STOCK`／`PREORDER`／`SOLD_OUT`／`UNAVAILABLE`，以及開放中預購批次摘要（opened/deadline/arrival）。

## 4. Phase 2 契約：搜尋（V26）

`GET /catalog/products`（舊參數 page/pageSize 照舊可用）新增：
`q`（名稱／作品／角色／品牌／製造商，ngram）、`brandId`、`workId`、`categoryId`、`manufacturer`、`availability`（IN_STOCK|PREORDER|SOLD_OUT，可多值）、`priceMin`、`priceMax`、`scale`、`material`、`productType`、`edition`、`limited`、`sort`（NEWEST|PRICE_ASC|PRICE_DESC|RELEASE_ASC|RELEASE_DESC|RELEVANCE）；page 1–1000、pageSize 1–48；未知參數忽略、非法值 400。`GET /catalog/facets`：各篩選可選值與計數（只算上架商品）。價格以啟用 SKU 最低價，可售狀態以 sku_inventory 與開放批次推導。

## 5. Phase 3–7 摘要（各階段開工前再補細節到此文件）

- **收藏**：`PUT/DELETE /customer/favorites/{productId}`、`GET /customer/favorites`（含目前 availability）、`POST /customer/favorites/merge`（訪客暫存，冪等）；App 走 `/mobile/customer/...`。
- **通知訂閱**：`GET/PUT /customer/notification-preferences`；`PUT/DELETE /customer/product-alerts/{productId}`（RESTOCK、PREORDER_OPEN、PREORDER_CLOSING、RELEASE）；事件仍寫 `notification_outbox`，`(event_kind, source_id)` 唯一防重；排程只掃狀態轉換，單 tick 上限；Email 走 `EmailSender` 介面（MOCK 預設、SMTP 待金鑰）。
- **SEO/GEO**：`/api/seo/sitemap.xml`、`/api/seo/head?path=` 供 nginx SSI 注入 title／description／canonical／OG／JSON-LD 與無 JS 可讀的商品摘要；robots.txt 允許 Googlebot、OAI-SearchBot、Bingbot，阻擋後台與會員頁；指南／FAQ／預購規則／材質知識頁；Search Console 設定文件。
- **合併／倉庫**：只合併同顧客、未付款或同付款階段、未出貨、配送目的地相同的訂單；合併只產生新的出貨單（shipment），不改原訂單金額與付款；運費差額以「待確認的運費調整」記錄，不自動扣款。倉庫：已付清已到貨的品項可轉入暫存（狀態 IN_WAREHOUSE），期限與到期提醒，選擇多件建立合併出貨單。
- **客服政策**：政策頁草稿／待審／發布，未審核前前台顯示「條款審核中」；客服單、售後案件、狀態與指派。
- **後台營運**：TOTP MFA（員工自願綁定、管理員可要求）、統計報表把 MOCK/測試付款與真實付款分開、銷售排行、低庫存提醒、備份還原文件。

## 6. 待啟用清單（需外部服務或作者決定）

正式 Email（SMTP／SES 等金鑰與寄件網域）、正式物流與超商 API、正式金流、第三方登入、正式網域與 Search Console 擁有權驗證、政策與條款的正式內容、nginx 部署契約變更（SEO SSI）、正式備份目的地。
