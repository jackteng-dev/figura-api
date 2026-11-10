# 已出貨整筆退貨演練 API（V10）

2026-10-07（Asia/Taipei）。只處理隔離demo的整筆模擬退貨，每筆訂單最多一次申請；拒絕後不重新申請。不指定正式期限、運費、手續費或部分退款政策，不安排寄送、轉帳或通知。前述限制是演練邊界，不能套用為正式商家政策。

base `/figura/api`；Result包裹`data`。顧客使用獨立CustomerPrincipal／Cookie／CSRF；員工使用原有staff realm＋URL/method權限，操作還會在DB鎖定並重新檢查啟用及授權。owner、actor、金額、時間均由伺服器取值，不接受未知欄位。UUID使用小寫標準格式；版本必須正整數，原因／說明為trim後非空、最多500字。時間為既有LocalDateTime合約，UI以台北時間顯示。

| Method／path | 身分／權限 | 請求與結果 |
| --- | --- | --- |
| GET `/customer/orders/{id}/mock-return` | 本人顧客 | `{enabled,canRequest,order}`，order含目前version與nullable returnProgress |
| POST 同path | 本人顧客＋CSRF | `{requestId,expectedOrderVersion,reason}` → `{order,replayed}` |
| GET `/admin/orders/{id}/returns` | `orders.returns.read` | `{mockEnabled,canReceive,canReject,canRefund,request,events}` |
| POST `/admin/orders/{id}/mock-return-receipt` | `orders.return.receive.mock`＋CSRF | `{requestId,expectedOrderVersion,note,disposition}` → `{event,order,current,replayed}`；current為退貨Detail |
| POST `/admin/orders/{id}/mock-return-rejection` | `orders.return.reject.mock`＋CSRF | `{requestId,expectedOrderVersion,note}` → 同上 |
| POST `/admin/orders/{id}/mock-return-refund` | `orders.return.refund.mock`＋CSRF | `{requestId,expectedOrderVersion,note}` → RefundPayloads.Applied：`{event,order,current,replayed}`；event為RETURN_REFUND financial audit，current為退款Detail，需再GET returns取得完整退貨audit |

普通demo員工只能讀；店長可讀與操作。顧客不能讀員工audit／原付款通知，員工不能冒用顧客申請。本人其他訂單不存在／其他owner皆404；未登入或realm錯誤401；權限／CSRF403；格式／未知欄位400；狀態、內容碰撞、版本或容量衝突409。不提供DELETE／PUT audit。

## 狀態與庫存

申請要求PAID、SHIPPED或DELIVERED、沒有未處理付款review、尚無申請。保存REQUESTED與order.version+1，不動付款或庫存。REQUESTED或RECEIVED期間禁止新的配送動作；歷史成功配送重送仍回目前狀態。

- REQUESTED → RECEIVED：店長明確選`RESTOCK`（可重新入庫）或`NO_RESTOCK`（不回補）；保存驗收時間、不可改的處置與audit、order.version+1。此步不退款、不動庫存。若申請後出現review仍可記錄驗收。
- REQUESTED → REJECTED：保存拒絕時間及audit、order.version+1；不動stock、不退款。沒有review時恢復配送，此演練不允許第二次申請。
- RECEIVED → REFUNDED：需PAID且仍已出貨／送達、無未處理review、唯一SUCCEEDED主付款、開啟退貨及退款功能。server快照全額退款。RESTOCK回補每line原quantity、reserved不變、inventory.version+1與每order/SKU唯一RETURN；NO_RESTOCK完全不寫庫存或RETURN。兩種都保存REFUNDED／closedAt／order.version+1、financial RETURN_REFUND及return REFUND兩份audit與return退款時間。

退貨退款保留paidAt、原SUCCEEDED付款與APPLIED簽章通知、訂單行與收件快照、原SHIPPED／DELIVERED進度／tracking／時間。不復活訂單、不重複扣庫。額外付款REVIEW_REFUND仍使用既有流程、不動庫存；全部處理後可繼續退貨退款。

## 原子性與重送

READ_COMMITTED；員工／live grant → owner → order → payment → sorted product/inventory，與既有庫存及退款鎖序一致。申請插入、版次與單一申請唯一鍵同transaction；驗收／拒絕state、version與audit同transaction；退款兩份audit、stock／RETURN、order及return state同transaction。任何稽核插入故障、全域UUID碰撞或回補超過1M都全部回滾；無成功ledger不報成功。

顧客申請requestId全域唯一，核對customer／order／原version／trim reason。員工退貨event requestId全域唯一，核對actor／order／action／原version／trim note／disposition；financial退款仍共用既有全域退款UUID及payment唯一键。RETURN_REFUND使用相同UUID寫兩份audit。不同namespace（顧客申請、員工退貨、financial）分開，不能推論跨所有業務UUID唯一。

相同操作重送回原event與目前狀態，不倒退，最多驗收一次／回補一次。相同ID改內容409；新ID或過期版次不覆寫。未知網路回應保留原UUID／body，原頁同操作重試並凍結說明／處置；收到ACK後清除pending，即使GET失敗也只要求重新核對。400／401／403／404／409後保存未送說明、先讀目前order／return再操作。pending只存在元件記憶體，重整後先核對，不提供離線佇列。

## 投影、migration與開關

Order View新增nullable`returnProgress`：`requestId,status,provider:'MOCK',reason,disposition,requestedAt,receivedAt,refundedAt,rejectedAt`。顧客只本人、安全投影不含staff actor／note／原通知；`refund`沿用既有安全金額／退款時間。員工returns Detail另回完整退貨audit。原因與說明以文字呈現，HTML不執行。

V10保留V1–V9 byte/checksum。新增mock_order_return與order_return_event；REFUNDED終態增加内部`return_refunded`guard：legacy未出貨退款false，新退貨退款true且保持已出貨／送達。guard不在公開DTO；只改status／shipping無法把legacy未出貨退款變成已出貨退款。DB CHECK驗收／退款明確要求非null處置，不能靠SQL UNKNOWN通過。整個demo仍只在隔離新空elpsy_shop_demo／已有明確demo history升級，不對原DBrepair／clean／baseline。

`MOCK_RETURNS_ENABLED`映射`shop.returns.mock-enabled`，未指定預設false。退款另外要求`MOCK_REFUNDS_ENABLED`；關閉寫入404，安全歷史可讀、can* false。已有可信私有work/demo.env跑`python3 scripts/init_returns_env.py`只補缺開關；Spring不自動讀檔，需程序環境提供。沒有金流／物流憑證或外部寄訊。

驗證見[本輪證據](verification/returns-2026-10-07.json)與[交接](HANDOFF.md)：真實MySQL／Redis交易與完整安全鏈、最終production JAR socket、最終dist Chromium。DB管理者仍可改資料，不宣稱防竄改或真實實體驗收完成。
