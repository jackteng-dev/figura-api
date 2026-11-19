# 商品多圖與 V24

2026-11-19。既有單主圖 API 與 Catalog `image` 五欄安全投影保留，App 不需同步更新。圖組最多六張，僅 DRAFT 可寫，所有商品／圖片 ID 均使用 canonical UUID。

## 端點與權限

沿用資料庫既有的固定權限路徑；不新增由使用者自訂 URL／method 的授權。

| 請求 | 行為 | 即時權限 |
| --- | --- | --- |
| GET `/admin/products/{id}/image?gallery=true` | 圖組、目前商品版本、最近 50 筆新舊圖片稽核 | `catalog.images.read` |
| POST `/admin/products/{id}/image` multipart | `operation=ADD/REPLACE`, `type`, `alt`, `requestId`, `expectedVersion`, `expectedActorId`, `file`；替換另帶 `imageId` | `catalog.images.upload` |
| POST 同路徑 JSON | `action=COVER`＋`imageId`，或 `action=REORDER`＋完整 `imageIds` | `catalog.images.upload` |
| DELETE 同路徑 `?gallery=true` JSON | `action=REMOVE`, `imageId`, `requestId`, `expectedVersion` | `catalog.images.remove` |
| GET `/catalog/products/{id}/image` | 僅已上架、有效商品的圖組；無員工與稽核欄位 | 公開 |
| GET `/{catalog\|admin}/products/{id}/image/{imageId}?width=320` | 當前圖組內圖片的指定衍生尺寸；不帶 width 讀來源 | 公開上架檢查／`catalog.images.content` |

以上均以 `/api` 為前綴，網站仍使用 Cookie／CSRF。所有新圖組寫入還需 `expectedActorId`，只作登入身分前置條件；服務在 transaction 內與已驗證 Principal 比較，稽核 actor 仍只取 Principal，不能由 body 指定。圖片類型為 `MAIN/ANGLE/FACE/DETAIL/SCENE/REAR`，是用途分類；主圖用獨立 `primary` 狀態，不由 type 決定。更改類型以替換操作保存新資產及稽核。

圖組 Image 包含 `id,url,alt,width,height,type,position,primary,variants`。Variant 僅 `url,width,height,contentType`，不回 objectKey、SHA、憑證或來源檔名。沒有衍生尺寸的舊單主圖仍可原尺寸讀取。

`Applied` 回傳 event、目前 detail 與 replayed。相同 UUID／actor／商品／原版本／操作／目標／順序／trim alt／來源 bytes SHA／類型重播只回原事件＋目前狀態。UUID 跨 legacy 及 gallery 共用 reservation；同 ID 不同內容回 409。版本過期不自動重試新內容。

## 交易、儲存與相容

- actor 即時啟用／grant → product lock → 全域 image command reservation → 每個 cleanup queue lock。商品版本、圖組、cover、variants metadata 與不可變 audit 在同一 SQL transaction。
- S3 PUT 前，沿用獨立已提交的 orphan reservation；寬限 10 分鐘。SQL 回滾或程序中斷可留孤兒，由 worker 重試清理；不宣稱 SQL／S3 分散式原子。worker 對 cover、gallery 及 active variants 都檢查引用。
- PNG／JPEG bytes 驗證後重編碼並移除 metadata；檔名不決定類型。以 JDK ImageIO 建立寬 320／640／1024 的 PNG 或 JPEG，維持長寬比、拒絕放大，與來源使用不同不可變 key。不存在的 width 回 404。
- API 未加 WebP／AVIF decoder／encoder；ComfyUI 本機素材流程的兩種格式不能直接送到此 API。網站使用安全 PNG／JPEG `srcset`，沒有宣稱線上 AVIF 已完成。
- 來源及衍生讀取檢查 bytes／hash、`nosniff`、`no-store`；移除、替換、下架或非目前圖組的網址回 404。沒有永久公開 bucket URL。
- 舊 cover 在第一次圖組操作時才 lazy materialize；不批次搬動所有商品。舊上傳仍替換目前 cover 並移出舊圖組引用，舊刪除移除原 cover 並遞補排序第一張，保留其他圖組。刪掉圖組主圖時選排序第一張作主圖；空圖組才為 null。排序與主圖選擇彼此獨立。
- audit 永不提供刪改 API，移除圖片只移除關聯，原 asset／variant metadata 與操作證據保留。

## 部署與回退限制

V24 新增 gallery、variant、command、gallery_event 四表，並從既有 image_event 初始化 command reservation。**V1–V23 bytes 不變**，保留原 cover／asset／event 表結構與 API。migration 位於 CD 受保護路徑 `src/main/resources/`。

此 PR 不能直接合併或自動部署。作者需先在伺服器端暫停 CD、備份、以隔離副本試套及演練舊版讀取，再執行受控 schema／contract switch。沒有執行上述伺服器步驟。

回退舊版可讀原 cover，但舊程式不知道 gallery／variants：**回退演練與回退期間需暫停圖片寫入及舊 cleanup worker**，以免舊 cover 替換／清理刪除新圖組仍引用的物件。不得將 additive schema 誤稱為舊版具備完整多圖寫入與清理能力；恢復新版本再開啟圖片管理。

## 驗收

`ImageIntegrationTest` 保留原單圖安全測試，新增多圖／上限／型別／尺寸／排序／主圖／舊版操作／下架、嚴格重播及全域衝突、併發、CSRF、即時 remove grant、故障回滾及 active cleanup。原始日誌與環境只在忽略的 `work/`，測試及 build 實際結果見 HANDOFF。

只使用新 `figura-gallery-20261119` Docker project、全新空 `figura_shop_demo`、獨立 Redis／SeaweedFS。未操作正式 DB，也沒有把既有 demo 資料搬入測試。
