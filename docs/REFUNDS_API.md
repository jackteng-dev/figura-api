# 模擬退款與異常付款核對（3B／V9）

只演練 MOCK 全額退款，不連金流、不轉帳、不自動通知。已出貨退貨、部分退款、手續費、正式商家政策另階段。V1–V8不可改；V9新增mock_refund_event、REFUNDED訂單終態、RETURN庫存事件與3個權限。非demo／沒有MOCK_REFUNDS_ENABLED預設false；關閉時寫入404，歷史仍可讀。

| API（/api前綴） | 權限 | 用途 |
| --- | --- | --- |
| GET /admin/orders/{id}/refunds | orders.refunds.read | 開關、canRefund、reviews及原通知、退款audit |
| POST /admin/orders/{id}/mock-refund | orders.refund.mock | PAID且UNFULFILLED、沒有未處理review的全額訂單退款 |
| POST /admin/orders/{id}/mock-review-refund/{paymentId} | orders.review.mock | 此訂單未處理REVIEW_REQUIRED付款的全額模擬退款 |

demo角色1店長含讀寫，角色2員工僅讀。員工Cookie realm、CSRF、啟用帳號與DB即時授權仍守門；顧客不可讀員工紀錄。body只有requestId（小寫UUID）、expectedOrderVersion（>=1整數）、note（非空、最多500字、trim後保存）；owner、actor、金額或任何未知欄位400。金額只取伺服器付款／訂單快照，貨幣TWD。

GET回傳 `{mockEnabled,canRefund,reviews,events}`。review含paymentId／amountTwd／currency／detectedAt／resolved／notifications；原通知含eventId／outcome／disposition／receivedAt，保留原成功／失敗證據。audit含requestId／orderId／paymentId／actorId／actorUsername／action／note／amountTwd／beforeVersion／afterVersion／createdAt。普通員工可讀內部說明，顧客不取得這些欄位。每筆訂單最多20個付款意圖及退款紀錄；原通知目前整筆讀取，尚未分頁與限流，沒有大資料量容量保證。

POST回傳 `{event,order,current,replayed}`，current為上述退款Detail，order為目前安全Order View。所有寫入READ_COMMITTED；先鎖啟用員工及授權列，再owner→order→payment→按product ID排序商品／SKU庫存。與付款／取消／到期／出貨共用owner及order鎖，退款與出貨競爭只有一方成功。

## 訂單退款

只處理唯一SUCCEEDED主付款且PAID／UNFULFILLED；所有review須已處理。每line將quantity加回原訂購量、reserved不變、inventory.version+1，新增唯一RETURN。整單status→REFUNDED、closedAt由DB保存、order.version+1，退款audit與庫存全部同transaction。回補後超過1,000,000拒絕409並全部回滾；先依稽核調整庫存再同操作retry。商品封存、顧客停用或原地址刪除不改付款快照，也不阻止店長退款。

REFUNDED保留paidAt、原SUCCEEDED付款及APPLIED通知；只有退款ledger表示後續演練退款。不能再出貨或建立新的付款，callback重送不能復活或扣庫。REFUNDED顧客直接cancel只是回目前終態，不再release。已出貨／已送達不可走此API；退貨驗收與庫存回補政策尚未實作。

## 異常付款核對退款

付款須屬於此訂單、REVIEW_REQUIRED且存在成功／REVIEW_REQUIRED原通知；不得處理INITIATED、FAILED、SUCCEEDED或他單意圖。此意圖沒有CONSUME／APPLIED，因此只寫REVIEW_REFUND與order.version+1，**不改訂單status、closedAt、paidAt、配送進度或庫存**。包含取消／逾時晚到成功、失敗後成功、第二個矛盾付款。

原付款status仍REVIEW_REQUIRED、原通知不改；payment_id唯一的REVIEW_REFUND表示已核對並模擬退款。同意圖後續通知仍保存原disposition且不重開核對、不結算或復活。履約與付款結算只檢查尚未有REVIEW_REFUND的review；全部處理後PAID可恢復出貨／送達或主訂單退款。若仍PENDING且未逾時，可用新的requestId及最新version建立新付款；已退款的異常意圖不能變成成功意圖。這些規則只適用固定MOCK交易ID，不代表真實金流對帳策略。

## 冪等、顧客投影與重試

退款兩種action共用全域requestId，payment_id唯一；同actor／order／action／目標付款（review）／原version／trim後note重送回原event及目前order，不再次回補。改任一內容409；跨actor／order競爭碰撞輸家的全部更新回滾。不存在的order／付款404，版次／不合狀態409，無權403；沒有刪改audit API。DB管理員仍可改資料，不宣稱防竄改。

Order View新增nullable `refund:{status:'REFUNDED',provider:'MOCK',amountTwd,refundedAt}`，只投影ORDER_REFUND；Payment View同樣新增nullable refund，投影該付款的兩種退款，包含已處理異常意圖。顧客只讀本人API，不含員工identity／note／原通知。付款的原始status與退款分開呈現，不再把已處理異常誤標成未處理。

員工頁共享操作說明與同操作重試；未知回應保留UUID及body、凍結切單／篩選／說明。即使已退款仍可retry舊操作；收到ACK後先清pending，後續GET失敗顯示已確認；已知4xx要求重讀並保留未送說明。pending只元件記憶體，重整先讀order／refunds，不持久保存收件個資或離線佇列。

驗證腳本：後端scripts/smoke_refunds.py；前端scripts/smoke_refunds_browser.cjs。只在可拋棄loopback demo、正式JAR／dist與真實MySQL／Redis執行；會寫合成資料，保留ledger並只清理自己的pending／封存商品。最新結果見HANDOFF與verification/refunds-2026-10-07.json。

庫存調整也先鎖啟用actor及inventory.adjust授權，再product／inventory，audit username取DB；避免庫存audit actor FK與退款actor鎖形成反向等待。


## V10最新補充：已出貨退貨

已出貨整筆退貨演練現已完成，合約以[RETURNS_API](RETURNS_API.md)為準；以上V9以前限制為當時歷史。Order新增nullable returnProgress，REFUNDED可保留原SHIPPED／DELIVERED（内部return_refunded guard）；financial新增RETURN_REFUND，保留原SUCCEEDED／APPLIED。REQUESTED／RECEIVED暫停新的配送，無未處理review才可退貨退款；RESTOCK退款時回補一次，NO_RESTOCK不動stock，驗收本身不回補。未出貨ORDER_REFUND及REVIEW_REFUND沿用原限制。沒有實際物流、转帳或正式商家政策。
