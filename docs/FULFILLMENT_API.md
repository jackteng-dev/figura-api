# 員工訂單與模擬履約（里程碑3A）

基底 `/ELPSY/api`。付款／物流均為演練；不會實際收款、建立託運或聯絡顧客。本輪不包含退款／退貨、異常付款核對處理、物流adapter、外部通知或outbox。配送費仍0為demo政策。

## 設定與migration

`shop.fulfillment.mock-enabled` 預設false；demo profile讀 `MOCK_FULFILLMENT_ENABLED`。新私有設定產生器讀取.env.example的true；已有私有檔可在後端根目錄執行 `python3 scripts/init_fulfillment_env.py`，只補缺少開關、不覆蓋既有值或憑證。關閉環境可讀既有訂單／配送歷史，新的出貨／送達POST404。付款mock另有獨立開關與私人HMAC金鑰。

Flyway新增V8：customer_order加fulfillment_status／shipment_tracking_id／shipped_at／delivered_at，預設UNFULFILLED且欄位null；order_fulfillment_event與穩定員工權限。V1–V7原內容與checksum不改。舊PAID訂單保留金額、paidAt、收件、庫存與付款紀錄，不推定已出貨。原DB先備份、隔離還原与規劃，不能直接套demo SQL。

## 權限與API

全部員工API使用staff Cookie realm與CSRF，不接受CustomerPrincipal。匿名／只有顧客Cookie401；未授權method或permission403。URL＋method依實際DB權限核對，隱藏按鈕只輔助操作。

| API | 權限code | demo角色 |
| --- | --- | --- |
| GET `/admin/orders` | orders.read.list，路由 `/admin/orders` | 店長／一般員工 |
| GET `/admin/orders/{id}` | orders.read.detail | 店長／一般員工 |
| GET `/admin/orders/{id}/fulfillment-events` | orders.audit | 店長／一般員工 |
| POST `/admin/orders/{id}/mock-shipment` | orders.ship.mock | 僅店長 |
| POST `/admin/orders/{id}/mock-delivery` | orders.deliver.mock | 僅店長 |

列表query：page=1–100000、pageSize=1–20（預設10）；可選status=`PENDING_PAYMENT|PAID|CANCELLED|EXPIRED`與fulfillmentStatus=`UNFULFILLED|SHIPPED|DELIVERED`，空字串／未提供表示不篩選，非法值400。按createdAt／id倒序。回 `{items,page,pageSize,total}`，只含id／status／version／totalTwd／fulfillmentStatus／createdAt／paidAt，不回收件個資、customerId或品項。詳情回 `{order,mockEnabled,canShip,canDeliver}`；order有已授權收件与品項快照。canShip／canDeliver表示**訂單與環境是否允許該狀態转换**，畫面另核對本人權限，POST仍會驗證。UUID不合法400，不存在404。

兩種POST body相同：`{requestId,expectedOrderVersion,note}`。requestId小寫UUID，expectedOrderVersion正整數，note非空、最多500字元，trim後保存。未知欄位（actorId／trackingId／owner／status等）、小數或缺欄位400。貨件編號由伺服器產生 `MOCK-`＋UUID，不接受瀏覽器或物流URL。操作者id與username取已認證Principal的id及目前DB身份，不採body或Principal的自稱username。

成功／重送均HTTP200，Result.data=`{event,current,replayed}`。current為目前Detail；event是該次原操作快照，包括requestId、orderId、actorId／actorUsername、action、note、beforeVersion／afterVersion、beforeStatus／afterStatus、trackingId、createdAt。Audit GET回最多兩筆SHIP／DELIVER，無編輯或刪除API；不能宣稱DB管理員無法修改資料。

## 配送與付款分開保存

| 配送狀態 | 可做操作 | 更新內容 |
| --- | --- | --- |
| UNFULFILLED | 僅PAID且沒有REVIEW_REQUIRED付款意圖可SHIP | SHIPPED、server trackingId、DB shippedAt、order.version＋1、SHIP ledger |
| SHIPPED | 僅PAID且沒有REVIEW_REQUIRED可DELIVER | DELIVERED、DB deliveredAt、order.version＋1、DELIVER ledger |
| DELIVERED | 不提供新SHIP或DELIVER | 原操作重送可讀原快照與目前状态 |

付款status仍PAID，paidAt／商品／地址／金額快照不改；**出貨與送達不再碰quantity、reserved、庫存version或stock event**。每order／action只有一筆event。未付款、取消、到期、送達在出貨之前、舊版次、新操作重複出貨409。PAID直接取消仍409，expiry不釋放已售出庫存。商品已封存、來源地址刪除或顧客停用不會取消已付款履約；員工依已保存訂單快照操作。

若已出貨之後才收到矛盾付款通知，既有出貨紀錄保留，後續送達被凍結等待人工核對；沒有自動退款或倒退配送狀態。真正配送資料與商家政策需另驗收。

## 冪等、交易與撤權

requestId全域唯一；相同actor＋order＋action＋原expectedOrderVersion＋trim後note重送，event回原操作、current回目前状态。即使已送達，歷史SHIP重送不倒退配送；相同ID改actor／order／action／版本／內容409。

READ_COMMITTED下先鎖目前啟用員工与所需DB授權資料（user_role／role_permission／permission讀鎖），再owner→order，與付款／取消／expiry的owner→order一致。寫入前重查啟用／授權，撤權或停用的舊Principal也被拒絕；授權列鎖使同時撤權在交易邊界序列化。不同員工／訂單撞全域ID時，唯一key衝突會回滾輸家已開始的進度變更。

進度、貨件、時間、order.version與ledger同一transaction；稽核故障全部回滾。不同出貨請求競爭只有一人成功，相同操作競爭返回同event。付款通知重送不能重設配送或重複扣庫存。

## 顧客安全投影與Web

原GET `/customer/orders`与`/{id}`新增fulfillment=`{status,provider:"MOCK",trackingId,shippedAt,deliveredAt}`；只有本人讀，外人ID404，不回internal note／staff identity。`/shop/orders`在已付款訂單顯示模擬出貨／送達与時間，手動「重新核對訂單」更新，沒有即時推播。

員工頁提供分頁、付款／配送篩選、詳情、說明、履約稽核。回應未知時保留同body／UUID，凍結表單與切換訂單，只能重試同一操作或核對。已知409保留未送說明、要求讀最新狀態；ACK收到後再讀資料失敗，明確顯示操作已確認，不製造第二筆。payload只在元件記憶體；離開／重整先讀現有配送與稽核，沒有跨頁離線佇列或localStorage收件資料。

驗證：`scripts/smoke_fulfillment.py`與前端`scripts/smoke_fulfillment_browser.cjs`，只在loopback可拋棄隔離demo，以正式JAR／dist、真實MySQL／Redis。瀏覽器在真正寫入成功後abort回應，再驗證同ID重試。證據見 [本輪紀錄](verification/fulfillment-2026-10-07.json)。未做正式環境／真實物流／大資料量分頁或負載保證。


## V9退款與核對補充（3B）

未出貨訂單全額模擬退款→REFUNDED／RETURN；原SUCCEEDED付款與通知不改。Order及Payment View新增nullable refund安全投影，付款歷史status與退款分開。異常付款以REVIEW_REFUND ledger表示已處理，履約與結算只凍結尚未處理review；原通知仍保留。詳見[退款API](REFUNDS_API.md)，本文件舊「退款未完成／review永久凍結」為當時範圍，以V9規則與HANDOFF為準。


## V10最新補充：已出貨退貨

已出貨整筆退貨演練現已完成，合約以[RETURNS_API](RETURNS_API.md)為準；以上V9以前限制為當時歷史。Order新增nullable returnProgress，REFUNDED可保留原SHIPPED／DELIVERED（内部return_refunded guard）；financial新增RETURN_REFUND，保留原SUCCEEDED／APPLIED。REQUESTED／RECEIVED暫停新的配送，無未處理review才可退貨退款；RESTOCK退款時回補一次，NO_RESTOCK不動stock，驗收本身不回補。未出貨ORDER_REFUND及REVIEW_REFUND沿用原限制。沒有實際物流、转帳或正式商家政策。
