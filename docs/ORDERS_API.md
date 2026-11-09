# 顧客現貨訂單與保留庫存（里程碑 2B）

基準：後端 `38ff4ff`、前端 `8375140`。以已驗證 CustomerPrincipal 取得本人 ID；員工 Cookie 不替代顧客，所有寫入仍需 CSRF。一般回應包在 Result.data。

## API

| 路徑 | 方法 | 請求／回應 |
| --- | --- | --- |
| `/api/customer/orders` | POST | requestId（UUID）、cartVersion、addressId、addressVersion、expectedSubtotalTwd；回 `{order,replayed}`，新建與重送均 HTTP 200 |
| `/api/customer/orders` | GET | page 從 1、pageSize 1–20；本人分頁 items／total |
| `/api/customer/orders/{id}` | GET | 本人訂單與快照；他人編號或不存在均 404 |
| `/api/customer/orders/{id}/cancel` | POST | expectedVersion >=1；回目前訂單，終態重送不再釋放 |

額外 body 欄位（owner、items、價格、運費、status）、小數整數、無效 UUID 拒絕。expectedSubtotalTwd 只用於比較顧客確認的金額，實際價格必須由鎖定的商品／SKU重新計算。地址必須存在、屬本人、未刪除且版本相同。購物車版本／價格／可售量／上架状态變動回 409，需要重新核對。

## 交易與狀態

- `PENDING_PAYMENT`：建立時由資料庫時間起保留 30 分鐘。配送費快照固定 0 TWD，是展示設定，尚非正式物流政策；不提供付款操作，也沒有實際扣款。
- 成功一次保存商品標題／款式／數量／單價、收件六欄與電話、總額、期限；保留量、庫存版本、RESERVE 紀錄與購物車清空／版本增加全在同一 MySQL transaction。任一寫入失敗全部回滾。後續地址刪除或草稿／價格修改不改訂單快照。
- customer 列鎖序列化本人 cart／address／order；商品 ID 排序後 product→SKU/inventory 鎖，共用既有商品／庫存寫入順序。READ_COMMITTED 在等待鎖後讀最新資料。各顧客同時買最後一件只一人成功；多款商品任何不足不能留下部分保留。
- requestId 以 customer_id＋request_id 唯一，同 ID＋相同購物車／地址版本／確認金額回原訂單**目前狀態**；內容不同 409。已取消／逾時重送不建立新保留。未成功且已回滾的請求可同 ID 再試。每人至多 20 筆待付款。
- `CANCELLED`／`EXPIRED`：終態，釋放保留量、庫存版本＋1、RELEASE 紀錄與訂單狀態一起提交；每款只有一筆 RELEASE。取消待付款需相同版本，終態重送回目前狀態。已到期取消會轉 EXPIRED。
- 到期排程 demo profile 已啟用，預設每 30 秒處理最多 100 筆；重啟後掃描資料庫補做，不依賴 JVM 記憶體計時。到期釋放也適用已停用會員。交易失敗保留待付款與庫存，下輪再試，記錄無收件內容的 error。大量積壓、單筆持續錯誤與告警／負載仍待營運里程碑。
- 非 demo 的 Flyway 與排程預設不啟用。正式／原 DB 必須先備份、隔離還原，按 migration 規劃升級；確認 V6 與 timezone 後以 `shop.orders.expiry-enabled=true` 啟用。不可將 demo SQL 直接套正式資料。

## 庫存語意

`quantity` 為實體數量，`reserved` 為待付款保留，`available=quantity-reserved`。V6 對既有 SKU 加 reserved=0，V1–V5 原內容與 checksum 不改。商品公開 available、購物車判斷都採可售量。庫存調整維持原 quantity 語意，不能降到 reserved 以下；DB CHECK 同樣保護 `0<=reserved<=quantity`。每次保留／釋放增加 inventory.version，舊後台表單須重新核對。庫存 GET 新增 reserved／available；調整稽核与訂單保留事件分開。

目前尚無付款後轉售出／扣實體量，不得將保留宣稱為售出。訂單／保留紀錄沒有刪改 API，DB 管理員仍可修改；不宣稱防竄改。訂單 FK 保留顧客與款式關聯，不提供硬刪有訂單顧客的操作。

## Web 與未知結果

`/shop/cart` 同時讀購物車與本人地址，確認目前金額、收件地址後下單。未保存數量必須先更新；版本／价格衝突保留畫面並要求重讀。下單回應不明保留原 requestId／body，凍結地址與車內容，提供同一操作重試與「我的訂單」核對；沒有自動重新生成 ID 再下單。重試資訊只在当前元件記憶體，離開／重整後先查訂單，不是跨頁離線佇列；本人資料不寫 localStorage。

`/shop/orders` 顯示本人價格、收件与商品快照、台北時間期限、分頁与取消。状态通过「重新核對訂單」更新。30分鐘到期後可能等待下一個掃描／積壓，頁面時間本身不是釋放保證。

## 驗證

隔離 MySQL8.4／Redis7，demo env 与工具鏈见 NEW_MACHINE_SETUP；完整 Maven package 不跳測試。新 `OrderIntegrationTest` 覆蓋來源變動／快照、越權／CSRF、最後一件、多商品反向加入、相同 ID 競爭、保留与釋放寫入失敗回滾、取消／逾時競爭與停用會員到期。兩項歷史 V3→V4／V4→V5 fixture 不設定舊 marker 時仍略過，不冒稱重做歷史升級。

```sh
python3 scripts/smoke_orders.py
# 前端根目錄，提供私有環境及既有 Playwright 路徑
node scripts/smoke_orders_browser.cjs
```

瀏覽器 expiry 情境只對本輪 UUID 合成訂單把 expires_at 調到過去，再讓真實排程釋放；需專用 loopback demo 的 Docker MySQL，使用 demo DB 憑證。測試啟動 JAR 時可 `--shop.orders.expiry-delay-ms=1000` 縮短掃描間隔，**不縮短產品 30 分鐘期限**。HTTP／browser只取消、封存本輪fixture，不清共用稽核。證據見 verification/orders-2026-10-07.json。

## 待補

mock 付款與 callback 重送、付款／逾時競爭、實體售出扣庫與履約／退款、實際運費／物流、正式金流與 HTTPS、本機 timezone／排程／重啟復原與壓測、訂單個資保存清除政策、限流與異常告警。SeaweedFS 選型不變，尚未部署／串圖片；預購與 App 不在此輪。


## V7付款補充（里程碑2C）

訂單View新增nullable paidAt與PAID狀態。模擬成功原子扣quantity与reserved並新增CONSUME；PAID不再expire／直接cancel（409）。閉合訂單晚到成功只記REVIEW_REQUIRED；詳見 [模擬付款合約](PAYMENTS_API.md)。本頁較早段落的「付款未接」保留里程碑2B歷史範圍，最新狀態以HANDOFF為準。


## V8模擬履約補充（3A）

Order View新增fulfillment安全投影（status／provider=MOCK／trackingId／shippedAt／deliveredAt）；付款status維持PAID。員工出貨与送達只改配送／order.version並寫audit，不再扣庫；原付款重送不重設配送。有REVIEW_REQUIRED凍結後續配送，已發生紀錄保留。詳見 [履約API](FULFILLMENT_API.md)，本文件較早「履約未完成」為各里程碑當時範圍。


## V9退款與核對補充（3B）

未出貨訂單全額模擬退款→REFUNDED／RETURN；原SUCCEEDED付款與通知不改。Order及Payment View新增nullable refund安全投影，付款歷史status與退款分開。異常付款以REVIEW_REFUND ledger表示已處理，履約與結算只凍結尚未處理review；原通知仍保留。詳見[退款API](REFUNDS_API.md)，本文件舊「退款未完成／review永久凍結」為當時範圍，以V9規則與HANDOFF為準。


## V10最新補充：已出貨退貨

已出貨整筆退貨演練現已完成，合約以[RETURNS_API](RETURNS_API.md)為準；以上V9以前限制為當時歷史。Order新增nullable returnProgress，REFUNDED可保留原SHIPPED／DELIVERED（内部return_refunded guard）；financial新增RETURN_REFUND，保留原SUCCEEDED／APPLIED。REQUESTED／RECEIVED暫停新的配送，無未處理review才可退貨退款；RESTOCK退款時回補一次，NO_RESTOCK不動stock，驗收本身不回補。未出貨ORDER_REFUND及REVIEW_REFUND沿用原限制。沒有實際物流、转帳或正式商家政策。
