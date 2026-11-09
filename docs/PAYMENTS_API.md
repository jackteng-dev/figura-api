# 模擬付款 API（里程碑2C）

本合約只作免費隔離演練，不會實際收款。實際金流 adapter、退款、人工核對處理與履約另做里程碑。基底 `/ELPSY/api`；所有金額是 TWD 整數元，配送費仍為展示設定0。

## 設定與升級

`shop.payments.mock-enabled` 預設 false；demo profile 讀 `MOCK_PAYMENTS_ENABLED` 與 `MOCK_PAYMENT_WEBHOOK_SECRET`。啟用時私有簽章金鑰至少32字元，未提供則啟動失敗；不放在 VITE_*、回應或 Git。新 `scripts/init_demo_env.py` 隨機產生金鑰；既有私有檔使用 `scripts/init_mock_payment_env.py` 只補缺欄位，不重設帳號、DB或既有金鑰。已明確設定 false／空值的欄位需自行管理，不被產生器覆蓋。

Flyway 新增 V7，V1–V6 SQL／checksum 不改。增加 paid_at、付款意圖與通知ledger、CONSUME事件。既有待付款／取消／到期訂單不回填付款、不推測是否收款。非demo原DB先備份、隔離還原與規劃升級，不能直接套demo SQL。

## 顧客操作

顧客 Cookie realm 與 CSRF 沿用；owner 僅取 CustomerPrincipal。匿名／員工401，外人已知ID仍404；未知body欄位與小數金額拒絕。停用顧客不能建立或模擬付款，合法已發出的簽章通知仍需處理。

| Method／路徑 | 請求 | 結果 |
| --- | --- | --- |
| GET `/customer/orders/{id}/mock-payments` | 本人訂單ID | `{enabled,canStart,items}`；關閉時 enabled=false、items空，不讀付款table |
| POST `/customer/orders/{id}/mock-payments` | `{requestId,expectedOrderVersion}` | `{payment,replayed}`；HTTP200 |
| POST `/customer/mock-payments/{id}/simulate` | `{eventId,outcome}`，outcome=`SUCCESS`／`FAILURE` | `{eventId,disposition,replayed,payment,order}`；HTTP200 |

付款View含 id／orderId／status／amountTwd／currency／provider=MOCK／createdAt／settledAt；不回簽章金鑰。金額与 transactionId=`mock_`＋付款UUID由伺服器依訂單快照產生；顧客不能指定價格、交易編號或owner。模擬端點由伺服器產生並驗證相同HMAC通知，不連外部金流。

同customer＋requestId重送同order與expectedOrderVersion，返回原意圖目前狀態，不再建立；改內容409。只接受仍未到期的 PENDING_PAYMENT／目前版次；每訂單最多20筆意圖，最多1筆INITIATED。所有先前意圖均FAILED才可建立下一筆；任何REVIEW_REQUIRED都暫停新意圖與既有意圖的成功結算。

## 簽章通知

POST `/payments/mock/callback`，Content-Type `application/json`。只有此**精確路徑的POST**免Cookie／CSRF，改以HMAC驗證；顧客simulate仍需CSRF。header：

- `X-Mock-Timestamp`：目前Unix秒、10位數，與伺服器時間差最多300秒。
- `X-Mock-Signature`：HMAC-SHA256小寫hex，訊息為 `timestamp + "." + 原始UTF-8 JSON`；私有金鑰與伺服器相同。不能先重排JSON再驗簽。

JSON欄位：eventId／paymentId為小寫UUID，transactionId=`mock_`＋付款UUID，amountTwd為非負整數，currency=`TWD`，outcome=`SUCCESS`／`FAILURE`。驗簽後仍核對伺服器付款交易／金額快照。未知欄位、格式錯誤400；錯誤／缺失／過期簽章401；通知字串超过8192字元413；交易／金額／同eventId改內容409。這是應用層字串長度檢查，正式代理仍需設定request body限制與限流。

回應僅 `{eventId,disposition,replayed,paymentId,orderId}`，不回收件個資或完整訂單。eventId全域唯一；相同解析內容重送返回原disposition，replayed=true；JSON空白或欄位順序不影響解析內容fingerprint。重送仍需新的有效時間／對原始JSON的簽章。跨付款重用eventId或同ID不同結果409，任何已開始的庫存／狀態更新都回滾。

## 狀態與交易規則

| 通知／目前狀態 | disposition／付款結果 | 訂單／庫存 |
| --- | --- | --- |
| INITIATED＋SUCCESS，訂單未到期且無待核對意圖 | APPLIED／SUCCEEDED | 訂單PAID、paidAt與version更新；quantity与reserved同時減訂購量、庫存version增加、每品項CONSUME一次 |
| INITIATED＋FAILURE | PAYMENT_FAILED／FAILED | 訂單仍待付款、保留直到原期限，可用新操作建立意圖 |
| FAILED＋新FAILURE | ALREADY_FAILED | 不改首次settledAt、不扣庫 |
| SUCCEEDED＋任意新通知 | ALREADY_SUCCEEDED | 不重複扣庫、晚到失敗不降級 |
| 已取消／到期＋SUCCESS、FAILED＋SUCCESS、或另一意圖已待核對 | REVIEW_REQUIRED | 不復活訂單、不扣實體量；若仍待付款保留到原期限 |
| REVIEW_REQUIRED＋任意新通知 | REVIEW_REQUIRED | 不自動解凍、退款或變更庫存 |

通知的FAILURE若訂單已閉合，只記失敗，不復活訂單。SUCCESS在結算前同步檢查DB期限並執行到期釋放，不依賴排程先跑。PAID不能顧客直接取消（409），也不再到期release；退款退貨未實作。

列鎖順序為owner→order→payment→按product ID排序的商品／SKU庫存。READ_COMMITTED中，所有品項quantity／reserved、CONSUME、PAID、付款結果與通知ledger同transaction；任何一項失敗整筆回滾。付款對取消／逾時競爭只能CONSUME或RELEASE，不可兩者都有。

## 前端與未知結果

`/shop/orders`明示「付款模擬，不會實際收款」，呈現失敗／成功／待人工核對與paidAt。建立或通知回應未知時保留原body／UUID，只能同操作重試或核對；重整／離開頁面後先查目前本人訂單與付款，沒有跨頁離線佇列。409要求重新核對，不盲目新增意圖。人工核對與退款入口尚未提供。

驗證腳本：後端 `scripts/smoke_payments.py` 使用獨立Python HMAC與真實socket；前端 `scripts/smoke_payments_browser.cjs` 在真正寫入後abort回應，再驗證同requestId／eventId重試。只允許loopback與可拋棄demo；合成訂單／稽核保留，不為cleanup刪共享歷史。結果見 [付款證據](verification/payments-2026-10-07.json)。


## V8模擬履約補充（3A）

Order View新增fulfillment安全投影（status／provider=MOCK／trackingId／shippedAt／deliveredAt）；付款status維持PAID。員工出貨与送達只改配送／order.version並寫audit，不再扣庫；原付款重送不重設配送。有REVIEW_REQUIRED凍結後續配送，已發生紀錄保留。詳見 [履約API](FULFILLMENT_API.md)，本文件較早「履約未完成」為各里程碑當時範圍。


## V9退款與核對補充（3B）

未出貨訂單全額模擬退款→REFUNDED／RETURN；原SUCCEEDED付款與通知不改。Order及Payment View新增nullable refund安全投影，付款歷史status與退款分開。異常付款以REVIEW_REFUND ledger表示已處理，履約與結算只凍結尚未處理review；原通知仍保留。詳見[退款API](REFUNDS_API.md)，本文件舊「退款未完成／review永久凍結」為當時範圍，以V9規則與HANDOFF為準。


## V10最新補充：已出貨退貨

已出貨整筆退貨演練現已完成，合約以[RETURNS_API](RETURNS_API.md)為準；以上V9以前限制為當時歷史。Order新增nullable returnProgress，REFUNDED可保留原SHIPPED／DELIVERED（内部return_refunded guard）；financial新增RETURN_REFUND，保留原SUCCEEDED／APPLIED。REQUESTED／RECEIVED暫停新的配送，無未處理review才可退貨退款；RESTOCK退款時回補一次，NO_RESTOCK不動stock，驗收本身不回補。未出貨ORDER_REFUND及REVIEW_REFUND沿用原限制。沒有實際物流、转帳或正式商家政策。
