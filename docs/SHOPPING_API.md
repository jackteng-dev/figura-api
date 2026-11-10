# 購物車與本人地址（里程碑 2A）

所有端點前綴 `/figura/api/customer`；僅驗證後的顧客 Cookie／realm／Redis session。員工 Cookie 不可代替會員，owner 只取 CustomerPrincipal。寫入需 X-XSRF-TOKEN；未知 body 欄位、小數整數、無效 UUID／版本拒絕。沿用 Result envelope，200 成功；400 格式、401 身分、403 CSRF／未允許端點、404 找不到本人資料、409 衝突或限制。

## 購物車

- GET `/cart` 回 `version, items, subtotalTwd, ready`。只讀、不建立假資料；items：skuId／productId、標題／款式、quantity、unitPriceTwd、lineTotalTwd、issue。不回 owner／精確庫存／稽核。
- PUT `/cart/items/{skuId}` body `{expectedVersion, quantity}`，**絕對數量** 1–99、最多 50 種 SKU。本人 customer 列鎖序列化整車變更／容量，product→SKU/inventory鎖核對最新上架／啟用／庫存，READ_COMMITTED 避免等待列鎖後讀到舊快照；不修改任何庫存。首次版本 0，每次成功 +1（包含相同數量）。
- DELETE `/cart/items/{skuId}?expectedVersion=N`，只刪本人，缺貨／下架仍可移除。不存在404，過期409；整車版本即使空車也保留，不能舊版本復活。
- 價格由 GET 重新讀伺服器當前上架 SKU 整數 TWD；subtotalTwd 使用 long，僅無 issue 款式加總、不含運費。`ready` 只代表此讀取快照無問題、非庫存承諾或可結帳保證；空車false。
- issue：UNAVAILABLE（下架／封存／停用）、SOLD_OUT、INSUFFICIENT_STOCK。下架保留加入時已公開的標題／款式快照，價格及 lineTotal=null，不洩漏未發布草稿編輯。缺貨／不足仍可見當下公開價格，但排除可用小計。
- 不增量重試、不自動覆寫409；相同 body／版本再送會409，不能重複累加。網路回應遺失先GET確認結果再決定動作，沒有 requestId ledger、離線待送或跨頁冪等承諾。產品頁把加入量轉成整車最新絕對量，未知結果暫禁再次加入；購物車頁失敗保留輸入、明確重新核對。

## 地址

- GET `/addresses` 只回本人未刪地址，最多10；不回owner。
- PUT `/addresses/{clientUUID}` body：expectedVersion、recipientName（1–80）、phone（8–15數字，可前置+）、postalCode（3／5／6數字）、city／district（各1–40）、street（1–200）。先限定台灣收件資料；不檢驗真實郵遞區號或行政區對應、不支援物流與預設地址。
- 新UUID expectedVersion=0，成功version=1；編輯必須當前版本，每次+1。同本人列鎖保護容量／競爭；外人已知UUID PUT／DELETE404。首次200與更新200相同，非新增每次新ID的POST。
- DELETE `/addresses/{id}?expectedVersion=N` 需N>=1；清除收件內容、保留deleted編號墓碑與+1版本，不再列出／不可重用UUID，避免舊請求復活或版本歸零覆寫。無訂單引用；未來訂單使用收件快照，不能依賴可改地址。
- 版本衝突／未知回應保留表單並停寫，需重新核對（會放棄未儲存輸入）。不在 localStorage 保存地址／密碼。刪除有UI確認；原資料移轉、保存策略與真實物流驗收待本地。

新增 Flyway V5；V1–V4 checksum 不改。新 DB 及既有隔離 V4 → V5 驗證，非原 DB migration。尚未有下單、價格快照、庫存保留、運費、金流或物流。

## 圖片方案

使用者指定 SeaweedFS，後續採通用 S3 SDK。此里程碑僅更新選型；舊 MinIO helper／SDK 未替換且沒有啟動任何物件儲存。圖片上傳的格式／大小／檔名、bucket隔離、預簽名網址與備份還原須獨立實測。
