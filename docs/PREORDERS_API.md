# 預購批次與顧客訂單 API

批次管理／查詢、顧客預購下單／配額保留與免費模擬付款／退款已實作（V15–V17）；到貨登記、整筆分配與模擬履約已實作（V18）。預購所有操作使用獨立供應數量，不改現貨訂單、購物車或 sku_inventory。

## 批次規則（V15）

- 單店、TWD；一批次固定對應一個商品及一個 SKU，不跨 SKU 或批次混單。多批次可引用同一 SKU，各有獨立供應配額。
- 狀態 DRAFT → OPEN → CLOSED；DRAFT 亦可直接 CLOSED 取消草稿。CLOSED 不重開、不刪除。截止時間到達時公開讀取呈現 EXPIRED，不偷偷改資料版本或製造員工稽核。
- 草稿可編輯批次名稱、純文字說明、整數單價、配額、截止與預估到貨區間；商品／SKU 在建立後不可更換。開放後全部條件及商品／SKU 名稱快照固定，後续延期另獨立合約。價格是店長明確設定的預購報價，不取顧客輸入。
- 開放時同交易核對商品 PUBLISHED、所屬 SKU enabled、主檔啟用及截止在未來；商品下架／封存或 SKU 停用後公開查詢 404。重新上架可再次顯示原已開放批次；原條件不重寫。
- 配額 1–100000、價格 1–99999999；held_quantity／paid_quantity 初始 0，由服務端管理，合計不能超過 capacity。V16由預購訂單交易寫入held，V17付款成功held轉paid、整筆退款釋放paid。公開 orderingEnabled 僅在OPEN、未過截止且還有配額時為true；付款僅本地MOCK。配額不是現貨可售量。
- deadlineAt 必須包含 UTC offset、精確到秒；服務轉為 +08:00 儲存／輸出。台北時間的截止日期不得晚於 arrivalFrom；arrivalFrom ≤ arrivalTo，2000–2100 年範圍；新增／修改／首次開放截止需在未來。到貨區間為估計，不宣稱保證到貨。

## 路徑與授權

均有 /api 前綴；正式 JAR context 為 /figura。

| Method | Path | 用途／permission |
| --- | --- | --- |
| GET | /admin/preorders | 分頁批次，preorders.read |
| GET | /admin/preorders/{id} | batch＋唯讀 events，preorders.detail |
| POST | /admin/preorders | 建立草稿，preorders.create |
| PUT | /admin/preorders/{id} | 編輯草稿，preorders.update |
| POST | /admin/preorders/{id}/open | 開放批次，preorders.open |
| POST | /admin/preorders/{id}/close | 截單／取消草稿，preorders.close |
| GET | /catalog/preorders | 匿名公開分頁，包含已開放及已截單批次 |
| GET | /catalog/preorders/{id} | 匿名安全詳情 |

店長預設全權、普通員工唯讀，顧客 cookie 不授權員工 API。寫入保留 Cookie／CSRF，交易內重新鎖定啟用 actor／grant；鎖順序 actor/grant → catalog master singleton → product → batch。共用 master guard 與商品操作維持一致順序，同時序列化不同 actor／batch 的全域 requestId。此單機 MVP 有全域寫入鎖，沒有大量負載驗證。

Write body：productId、skuId、name、description、priceTwd、capacity、deadlineAt、arrivalFrom、arrivalTo、expectedVersion、requestId、note。Command body：expectedVersion、requestId、note。未知欄位、浮點整數與偽造計數／actor／狀態拒絕。新批次 expectedVersion=0。

首次成功 201；同 actor／action／target／trim內容／原version／正規化時間的 requestId 重播 200，回原 event 與目前 current，不重新建立或退回歷史狀態。同 UUID 用不同操作／內容／actor／target 或版本過期 409。批次異動與 before/after audit 同交易，稽核失敗全部回滾。API 不回 fingerprint；無刪改 audit API，DB 管理者仍可改資料，非防竄改稽核。

公開只回 id、productId、名稱／說明、商品與款式快照、預購價格、剩餘配額、截止與到貨區間、有效狀態、orderingEnabled；無員工、內部原因、版本、SKU 編碼、保留／已付細項。Cache-Control:no-store。公開查詢無登入刷新或員工導向。

員工 UI 未知 ACK 保留原 UUID/body、凍結編輯及其他寫入，原操作重試；ACK 後 GET 故障只重讀。409 保留未送資料與原因，重讀後需明確重新開啟編輯／詳情。pending 僅記憶體，重整後先核對稽核。唯讀批次頁不查商品選單；編輯選單需 catalog.read.list。商品選單讀完最多 100×12 筆，超出會明確報錯，不默默截斷；大量商品搜尋另里程碑。

## 顧客預購訂單（V16）

| Method | Path | 用途 |
| --- | --- | --- |
| POST | /customer/preorders | 一訂單一批次，首次201、同操作重播200 |
| GET | /customer/preorders | 本人分頁（page≥1、pageSize1–50） |
| GET | /customer/preorders/{id} | 本人不可變條件與收件快照／目前狀態 |
| POST | /customer/preorders/{id}/cancel | 取消未付款保留，200／衝突409 |

均需有效customer cookie；POST需CSRF，員工身分不授權，別人訂單／地址404；V17新增獨立staff預購訂單管理與付款路徑，無刪改ledger API；V18新增獨立預購出貨路徑，仍不能用現貨訂單路徑處理這些ID。

Place body：requestId、batchId、quantity（整數1–100）、addressId、addressVersion（≥1）、expectedSubtotalTwd（正整數long）。owner從Principal取，服務核對本人未刪地址版本、目前上架商品／啟用SKU及主檔、OPEN／未過截止與獨立可用配額。價格取已開放批次固定報價，客戶端expectedSubtotalTwd只是核對值；運費0僅展示。開放後條件固定，因此不以會隨保留數量變更的batch version作價格確認。不同UUID代表不同訂單，切勿未知回應時產生新UUID。

快照包括批次名稱、商品／SKU名稱、單價、數量、subtotal／shipping／total、deadline、預估arrival、六欄本人Shipping。單價上限99999999、數量100，total以long計算避免INT overflow。原地址刪除、商品改名／下架、批次截單不重寫歷史訂單。輸出時間含+08:00。

保留到期取DB CURRENT_TIMESTAMP(6)+30分鐘與批次deadline較早者。未付款狀態PENDING→CANCELLED或EXPIRED，version從1開始；讀取無暗改，排程預設demo啟用、每30秒取最多100筆，每筆獨立短交易。逾時至worker處理前仍占配額，不表示額外可售。與現貨共用shop.orders.expiry-enabled／expiry-delay-ms開關；非demo預設未啟用，啟用預購環境必須配置排程。disabled owner仍能SYSTEM expiry，避免永遠占位。batch截單不自動取消既有保留。

Cancel body：requestId、expectedVersion。核對目前PENDING與version；若保留已到期則以EXPIRE結束，同樣一次釋放。新操作指向終態或過期version409，不重開。相同customer的PLACE／CANCEL共用requestId空間，以操作／目標／原body SHA核對，改內容或跨target409；不同customer UUID互不授權、不洩漏別人資料。原成功命令重播目前狀態，不重新保留，亦不復活終態。UUID只在成功交易保存，已回滾命令可原body重試。

鎖順序：place customer→catalog master guard→product→batch；cancel／expire customer→batch→order。讀reference不先鎖order，不能反向取得batch；customer鎖也與本人地址修改互斥。quota held、batch version、order、preorder_order_event不可變delta／before-after／actor_kind及outbox同transaction；任何寫入故障全部回滾。資料庫held+paid≤capacity、非負、amount／期限／狀態check及event唯一；取消／worker競爭只釋放一次。release不依賴商品仍上架或批次仍OPEN，從未取得或變更physical inventory。

通知PREORDER_PLACED／PREORDER_CANCELLED／PREORDER_EXPIRED沿用本地MOCK queue／receipt／重試，不寄送真人。V16保留原V14 rows／payload，outbox.order_id與preorder_order_id恰一非NULL，typed FK仍有效；原orderId投影與篩選以兩者COALESCE。六欄safe payload沿用：orderId、eventKind、orderVersion、orderStatus、fulfillmentStatus（UNFULFILLED）、totalTwd，無收件內容／owner／內部note。批次管理操作仍只有batch audit，不假造order notification。

UI：公開批次符合條件才出下單入口，結帳需登入；/shop/preorders/{batchId}/checkout、/shop/preorder-orders。unknown ACK保留原UUID/body凍結其他操作、只重試原命令；ACK清pending後GET失敗只重讀；409保留quantity並禁送，重讀最新地址／配額後明確再送。頁面記憶體pending，離開需明確確認；重整後先本人訂單清單核對。預購頁不顯示現貨MockPaymentPanel。

## 模擬預購付款與退款（V17）

| Method | Path | 用途／permission |
| --- | --- | --- |
| GET / POST | /customer/preorders/{id}/mock-payments | 本人付款選項／建立意圖 |
| POST | /customer/preorder-payments/{id}/simulate | 本人模擬成功或失敗 |
| POST | /preorder-payments/mock/callback | 原始 JSON 私有 HMAC 通知 |
| GET | /admin/preorder-orders | 摘要分頁，preorder-orders.read |
| GET | /admin/preorder-orders/{id} | 授權收件詳情／付款證據／refund audit，preorder-orders.detail |
| POST | /admin/preorder-orders/{id}/mock-refund | 未到貨整筆退款，preorder-orders.refund.mock |
| POST | /admin/preorder-orders/{id}/mock-review-refund/{payment} | 異常成功核對退款，preorder-orders.review.mock |

付款及退款分別沿用shop.payments.mock-enabled、shop.refunds.mock-enabled（環境MOCK_PAYMENTS_ENABLED／MOCK_REFUNDS_ENABLED），預設false；只記錄本地MOCK，不實際收款、轉帳或通知真人。V17原先只有到貨／配送guard；V18已加入登記、整筆分配及模擬履約，見下節。

Start body只有requestId、expectedOrderVersion；simulate body只有eventId、outcome（SUCCESS／FAILURE）。金額與TWD、transactionId由伺服器訂單快照決定，不接受owner／amount／status。付款requestId為customer內獨立空間，與PLACE／CANCEL命令空間分開；相同ID核對order及原expectedOrderVersion，重播原意圖目前狀態，改內容409。只有有效PENDING、未過期限、無未處理review、既有意圖皆FAILED或已核對退款才可新建；每單最多20個意圖。失敗不释放held，可另建意圖；一單最多一個SUCCEEDED。

只有精確POST /api/preorder-payments/mock/callback免Cookie／CSRF，使用X-Mock-Timestamp及X-Mock-Signature：HMAC-SHA256(timestamp + '.' + 原始JSON)、300秒窗口、8192 byte上限。JSON只有eventId、paymentId、transactionId、amountTwd、currency、outcome；未知欄位／浮點整數拒絕，金額／交易需等於快照。全域eventId核對內容，原event重播原disposition及目前狀態；不同內容或不同付款409。callback回安全IDs／disposition／replayed，無shipping或staff note。現貨callback不能處理預購payment ID。

付款鎖順序customer→batch→order→payment，與取消／expiry一致。成功PENDING→PAID、paidAt／version、held減quantity／paid增quantity、PAY quota ledger及safe outbox同transaction，完全不動sku_inventory／cart／現貨order。若已過期，在同交易先expiry；取消／逾時／失敗意圖的晚到成功REVIEW_REQUIRED，order version+1，保留成功通知證據，不復活order、不占回已释放配額。重複成功不再结算。PAID不被expiry或cancel釋放。

Refund body只有requestId、expectedOrderVersion、note（trim後必填、最多500字）。店長才可寫，普通員工唯讀；交易內重新鎖定啟用actor及live grant，再customer→batch→order→payment。两種退款共用全域requestId，核對actor／order／action／payment（review）／原version／trim note；原命令重播原audit及目前detail，同ID改內容409，每payment最多一次退款。

ORDER_REFUND僅PAID、NOT_ARRIVED、UNFULFILLED且無未處理review：paid減quantity、REFUNDED／closedAt／version、REFUND quota ledger、refund audit與outbox同交易。REVIEW_REFUND須有原SUCCESS／REVIEW_REQUIRED證據：保留payment與event，僅order version+1及refund audit／outbox，不動order狀態、held／paid或physical。任一步失敗全部回滾；已截單或停用owner仍能授權核對歷史。顧客只本人退款安全金額／時間，不回actor或內部note；staff摘要清單也不含shipping或owner，授權detail才含收件快照。

通知新增PREORDER_PAYMENT_SUCCEEDED／FAILED／REVIEW_REQUIRED、PREORDER_REFUNDED／REVIEW_REFUNDED；同kind＋payment ID去重，六欄safe snapshot與業務同交易，typed parent沿用V16。退款不刪原付款／異常證據。

UI：顧客本人清單使用獨立PreorderPaymentPanel；staff /admin/preorder-orders查詢與退款。未知ACK凍結其他操作，保留原UUID/body（simulate原eventId/outcome）；收到ACK先清pending，後續GET失敗僅重讀、不重送寫入。409保留note並禁送，重讀後明確重新開啟詳情；頁面pending只記憶體，重整先本人清單／staff audit核對。

## 預購到貨、整筆分配與模擬履約（V18）

| Method | Path | 用途／permission |
| --- | --- | --- |
| GET | /admin/preorder-arrivals | 安全批次供應清單，preorders.arrivals.read |
| GET | /admin/preorder-arrivals/{id} | 供應數量與不可變稽核，preorders.arrivals.detail |
| POST | /admin/preorder-arrivals/{id}/receive | 登記到貨，preorders.arrivals.receive |
| POST | /admin/preorder-orders/{id}/allocate | 整筆分配，preorder-orders.allocate |
| POST | /admin/preorder-orders/{id}/mock-ship | 模擬出貨，preorder-orders.ship.mock |
| POST | /admin/preorder-orders/{id}/mock-deliver | 模擬送達，preorder-orders.deliver.mock |

普通員工唯讀，店長才可操作；顧客不能用staff路徑，POST需Cookie／CSRF，actor只Principal且交易內重查啟用及live grant。到貨／分配為資料登記；ship／deliver沿用shop.fulfillment.mock-enabled（MOCK_FULFILLMENT_ENABLED），預設false、不建立真物流託運。到貨須人工核對實際收到商品，網站不代表已驗收實體。

供應獨立於sku_inventory與現貨order。累計received、allocated、shipped满足0≤shipped≤allocated≤received≤capacity；unallocated=received−allocated、onHand=received−shipped，onHand含已分配待出貨。不會因付款、到貨或分配增加現貨可售量，也不借用現貨reserve／consume ledger。訂單PAID及paid quota在履約後保留，配送是獨立狀態，不釋放已售供應配額。

Receive body：requestId、expectedBatchVersion、quantity（整數1–100000，累計不超capacity）、note（trim後必填，最多500字）。草稿拒絕、OPEN及CLOSED可收貨，包括已過deadline；只能正向增加，不自動取消或轉成現貨，原批次terms不改。

Allocate body：requestId、expectedOrderVersion、expectedBatchVersion、note。只整筆quantity分配，不接受顧客或staff自訂數量／owner／price。需PAID、無待核對付款、NOT_ARRIVED、UNFULFILLED與實際unallocated足夠；allocated_quantity=quantity、allocatedAt、arrivalStatus=ARRIVED、order version及batch allocated/version同交易。不同訂單搶最後到貨最多一方成功。已取消／逾時／退款／未付款不可分配；別批次同SKU不能混用到貨量。

Ship／Deliver body：requestId、expectedOrderVersion、note。SHIP需PAID、無review、整筆已分配且UNFULFILLED；只扣預購在庫（batch shipped累計增加）、生成mock_pre_ server貨件／shippedAt與SHIPPED，不改現貨quantity或reserved。DELIVER需PAID、無review且SHIPPED，只記DELIVERED／deliveredAt及batch/order版本，保留原貨件及paidAt，不再扣到貨。晚到成功在分配後仍阻擋出貨，在出貨後阻擋送達，須先核對退款原異常付款。

鎖順序：live actor/grant→customer→batch→order（allocate／ship／deliver），與money/refund維持一致；RECEIVE為actor/grant→batch，不反向等customer。與未到貨退款競爭由order/batch锁及version確保最多一方成功；一旦整筆分配，既有未到貨退款guard拒絕，不自動退回分配。到貨後取消、釋放分配／移為現貨、已出貨預購退貨另擴充，不套現貨refund或shipping API。

四類履約命令共用新的全域requestId空間（與batch管理及money退款空間分開）；SHA核對actor/action/target/原version、allocation原batch version、trim note及receive quantity。同UUID原body重播原event與目前狀態，配送不倒退；改內容／actor／目標409，每order的ALLOCATE／SHIP／DELIVER各最多一次。跨batch/actor同ID碰撞全交易rollback。供應與訂單版本、不可變fulfillment event及safe outbox同交易，audit／outbox失敗全部回滾；無刪改ledger API。

RECEIVE只batch audit；ALLOCATE／SHIP／DELIVER各保存PREORDER_ALLOCATED／SHIPPED／DELIVERED六欄safe outbox，不含收件PII／actor／note。顧客本人order.fulfillment安全投影：allocatedQuantity／allocatedAt、provider=MOCK、trackingNumber／shippedAt／deliveredAt；無staff event或內部note，別人404。staff預購order detail新增fulfillment供應／canAllocate／canShip／canDeliver與audit；原shipping只授權detail可讀。

UI：/admin/preorder-arrivals登記及稽核，/admin/preorder-orders整筆分配及模擬履約，/shop/preorder-orders本人安全進度。unknown ACK固定原UUID/body凍結其他操作、只retry同操作；ACK後GET失敗僅讀，不再POST。409保留數量／note，重讀再明確重開；pending只記憶體，重整先核對audit。到貨登記為持久資料，本版不支援任意修改／撤回。

## MVP完成範圍與後續

原四項預購MVP：批次／配額、與現貨分離、整筆mock付款取消退款及通知、到貨分配與出貨前核對，已配對V18完成。下一條主線為顧客App。訂金尾款、延期、部分退款、到貨後退貨與釋放分配為選擇性擴充，不作本版整筆預購MVP的隱藏必要條件。

真金流物流通知、商家素材及政策、HTTPS部署及原資料preflight／備份／隔離restore仍需正式營運驗收。每個里程碑真隔離MySQL／Redis／Seaweed測試、HTTP／Chromium、两端production build、交接及commit/push。V1–V18發布後不可改；原DB不baseline/clean/repair。DB管理者可改資料，audit非防竄改；尚無大流量或多節點負載驗證。
