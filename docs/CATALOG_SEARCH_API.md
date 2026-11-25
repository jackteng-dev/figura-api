# 商品規格與搜尋 API（Commerce V2 Phase 1–2，V25／V26）

前綴 `/figura/api`，回應 `{code,message,data}`。公開端點不需登入。

## 商品規格（`specs`）

管理端 `POST /admin/products`、`PUT /admin/products/{id}` 可帶 `specs`（省略＝不變更既有規格，舊客戶端相容；提供＝整組取代，未填欄位存為「未提供」）。

| 欄位 | 型別 | 說明 |
|---|---|---|
| characterName | string ≤120 | 角色名稱 |
| manufacturer | string ≤80 | 製造商 |
| scale | `1/1`…`1/144`、`NON_SCALE` | 比例（白名單見 `CatalogPayloads.SCALES`） |
| heightMm | 1–5000 | 全高（mm） |
| dimensions | string ≤160 | 其他尺寸說明 |
| materials | `PVC`、`ABS`、`RESIN`、`POLYSTONE`、`METAL`、`FABRIC`、`OTHER` | 真實商品材質 |
| productType | `PREPAINTED`、`ACTION_FIGURE`、`MODEL_KIT`、`GARAGE_KIT`、`PRIZE`、`BLIND_BOX`、`STATUE`、`PLUSH`、`OTHER` | 商品類型 |
| edition | `STANDARD`、`LIMITED`、`BONUS`、`DELUXE` | 版本 |
| limited | boolean | 限定商品 |
| accessories | string[] ≤30（每項 ≤80，空白項目會被略過） | 配件與附屬品 |
| releaseDate／releaseMonth | `YYYY-MM-DD`／`YYYY-MM`（擇一） | 預計發售 |
| specNote | string ≤2000 | 規格補充 |
| contentOrigin | `REAL`、`AI_CONCEPT` | AI 虛構展示商品標示 |
| conceptMaterials | 同 materials 代碼 | **只有 AI_CONCEPT 可填**，是設定描述不是製造規格 |

公開商品（列表與詳情）新增：`specs`（AI_CONCEPT 的 `materials` 一律為空）、`contentOrigin`、`availability`（`IN_STOCK`／`PREORDER`／`SOLD_OUT`，規則：任一啟用 SKU 有可售量＝現貨；否則有開放中且未截止、仍有配額的預購批次＝預購；否則售完）、`minPriceTwd`、`preorder`（`batchId, openedAt, deadlineAt, arrivalFrom, arrivalTo, priceTwd, orderingEnabled`，無則 null）、`updatedAt`。

## 搜尋 `GET /catalog/products`

只帶 `page`、`pageSize` 的舊呼叫行為不變（最新上架在前）。

| 參數 | 說明 |
|---|---|
| q | ≤80 字；比對名稱、角色、作品、品牌、製造商；空白分隔的每個詞都必須符合；1 個字時改用包含比對 |
| brandId、workId、categoryId | UUID，可重複或逗號分隔（OR） |
| manufacturer | 製造商名稱，可多值 |
| availability | `IN_STOCK`、`PREORDER`、`SOLD_OUT`，可多值 |
| priceMin、priceMax | 以最低啟用 SKU 價格比對 |
| scale、material、productType、edition | 白名單代碼，可多值；material 只比對真實商品 |
| limited | `true` 只看限定 |
| sort | `NEWEST`（預設）、`PRICE_ASC`、`PRICE_DESC`、`RELEASE_ASC`、`RELEASE_DESC`、`RELEVANCE`（有 q 時預設） |
| page、pageSize | page 1–1000，pageSize 1–50 |

非法代碼、UUID、價格範圍、分頁或排序回 400；未知參數忽略。發售日排序以 `releaseDate`，只有月份時視為該月 1 日，未提供者排最後。

## 篩選選項 `GET /catalog/facets`

參數同搜尋（不含 sort、分頁）；`availability` 套用到其他選項與 total／價格範圍，但 `availability` 本身的筆數不套用（方便切換）。回傳在目前條件下各選項的 `{value,label,count}`：brands、works、categories、manufacturers、availability、scales、materials、productTypes、editions，以及 `priceMin`、`priceMax`、`total`。只計上架商品。

## 索引（V26）

`ft_product_search(search_text) WITH PARSER ngram`、`(status,brand_id)`、`(status,work_id)`、`(status,category_id)`、`(status,release_date,release_month)`、`(status,product_type,scale)`。`search_text` 由伺服器在商品新增／編輯時維護，不接受用戶端寫入。
