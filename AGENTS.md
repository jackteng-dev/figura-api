# ELPSY Shop 工作規則

本倉庫是正式維護的 A 版。產品方向：公仔／收藏品單店，現貨交易完成後再加預購，最後接顧客 App。前後端分別是 Shop_back（Vue／TypeScript）與 shop_bak_ap（Spring Boot／MyBatis）；各自 main、交接、測試與提交。B 版 ELPSY_Shop-WEB／AP（master）僅作唯讀參考。本次工作只涉及本作品，不改發呆亭、Atlas、日日選物或展示平台。

## 每輪流程

1. 先 fetch 遠端 main，保留未提交修改；若 Git CLI 無法驗證，使用已連線 GitHub 工具讀取並同步實際 commit／tree，不猜測最新狀態。
2. 先讀遠端 `docs/HANDOFF.md`；第一次沒有文件時如實記錄。
3. 再查看 status、branch、最近 log、diff（含 staged）；正常後以遠端 main fast-forward 開始。分歧先查明，不丟棄工作。
4. 每個獨立階段跑相關測試與 production build；前端 `npm run build` 包含型別檢查，後端 `mvn package` 不跳測試。
5. 成功後更新 HANDOFF，至少寫完成內容、重要檔案、測試／build 結果、未完成、已知問題、下一步。失敗／未跑不能寫成功。
6. 每個里程碑就在功能分支 commit、push 並開 PR；main 受分支保護（2026-11-09 起：必須 PR、`verify` CI 通過才能 merge，管理員同受限，禁止 force push）。CI 通過後一般 merge，由自架主機 CD 自動部署。
7. 最後確認 status 乾淨、PR 已合併或狀態已記錄，合併後本地 main 與遠端一致。下一輪一律重新以遠端為準。

## Context 與保存

- 長任務若可取得 context／token／usage，主動檢查；沒有介面不能捏造數字，保守安排保存點。
- 不等限制耗盡才交接。下一階段前預留測試、build、交接、commit、push、遠端核對所需額度。
- 判斷不足以安全完成下一階段時，立即停止新增大型修改，優先驗證、更新 HANDOFF、在功能分支 commit、push 並開 PR。
- 交接保存優先於實作。每個可獨立驗證里程碑先保存，再開下一階段，不累積到整個大型任務結束。
- context compaction／資訊遺失／狀態不明時，從 repository、status、diff（含 staged）、log、HANDOFF、遠端重建實際狀態，不能僅依摘要繼續。
- 檢查或推送受阻就清楚記錄原因及續作方式，保留成果，不偽稱已完成或乾淨。

## 環境與範圍

使用免費開發方案；需要付費且無免費替代時先告知。遵守當前 proxy／網路政策；容器不得繞過政策。帳密、JWT 金鑰、資料、依賴、模型、build、原始日誌留在忽略路徑。私有 `.env` 不自動載入 Spring。

測試只能用明確隔離的 DB／Redis；demo SQL 只建新空 `figura_shop_demo`，不可拿去套原有 DB。demo 固定用戶名不是固定密碼；密碼由私有環境提供，不能重設已有用戶。不得把占位頁、mock 付款、localhost build 或合約測試宣稱成正式管理功能、實際收款、部署或 App 真機完成。

真機、原資料移轉、商家憑證、實際服務驗收先列 `docs/CLOUD_LOCAL_FOLLOWUP.md`。正式 HTTPS Cookie 預設 Secure；只有 loopback HTTP 展示可明確改 false。

## 顧客身分與 migration（2026-10-07）

顧客與員工使用獨立表、Cookie、JWT realm 與 Redis namespace；本人資料 ID 僅取已驗證 Principal，不能信任 body／query 的 owner ID。顧客入口 /shop 與 /api/customer，舊 /api/user/* 已停用。

已套用的 Flyway V1／V2／V3／V4／V5／V6／V7／V8／V9／V10／V11／V12／V13／V14／V15／V16／V17／V18 不改內容或 checksum；後續 schema 以新增版本 migration 保存。demo 限新空隔離 figura_shop_demo，寫入前核對名稱，非 demo 預設關閉；既有無 history DB 不自動 baseline／clean，先備份與隔離還原後規劃。

## 庫存與稽核（2026-10-07）

庫存由獨立調整API變更，商品DTO不能指定數量。actor僅取認證Principal；數量、版本與稽核在同一transaction，紀錄失敗必須回滾。相同requestId／內容／actor重送不得再增減庫存；過期版本拒絕，不能自動覆寫。歷史重送回操作快照，前端重讀目前數量；公開只顯示available，不回庫存明細或操作者。無API刪改異動，測試僅封存自己的有歷史fixture，不能為cleanup刪共用稽核。

## 購物資料與儲存選型（2026-10-07）

購物車／地址 owner 只取 CustomerPrincipal；不得接受客戶端價格、owner 或庫存。購物車絕對數量更新與移除核對整車版本，不自動重送未知結果；先重讀核對。購物車不保留庫存，結帳須另做交易核對。地址有版本與本人隔離、刪除清除收件內容並保留編號墓碑，防止舊請求復活。商品主圖已使用免費自架 SeaweedFS＋通用 S3 SDK；舊 MinIO helper／依賴已移除，不啟動 MinIO 作新方案。

## 訂單與保留庫存（2026-10-07）

新V6 migration，不改V1–V5 checksum。quantity為實體、reserved為保留、available為差額；讀可售量，員工調整不可低於保留。訂單owner只CustomerPrincipal；snapshot／reserve／event／清車同transaction，取消與逾時release／event／state同transaction；相同customer＋requestId返回同筆目前狀態，不復活終態。寫入需customer鎖，再按product ID排序鎖定庫存，避免多款反向死鎖。測試合成訂單／audit不刪共享歷史；未知回應重試同一操作。金流未接，配送費0僅展示。


## 模擬付款（2026-10-07）

新V7，不改V1–V6。只做免费mock，不接真實金流。金額／交易ID取伺服器訂單快照，owner取CustomerPrincipal；只有精確POST /api/payments/mock/callback免Cookie／CSRF，以私有HMAC＋5分鐘時間窗驗證原始JSON，其他写入仍CSRF。requestId與eventId冪等，未知回應重試同操作；同ID改內容409。成功扣quantity與reserved、CONSUME、PAID／paidAt、付款與通知ledger同transaction，任一步失敗全部回滾。PAID不直接cancel／expire；閉合訂單或失敗意圖晚到成功記REVIEW_REQUIRED，不復活／不扣庫／不自動退款；有待核對的訂單暫停結算。產生器只補缺少mock設定，不重設私有憑證。人工核對／退款／履約另做里程碑。


## 模擬履約（2026-10-07）

V8只新增配送欄位、履約ledger與orders.*穩定權限；V1–V7不可改。付款仍PAID、配送獨立UNFULFILLED→SHIPPED→DELIVERED；只有已付款且沒有待核對通知可操作，出貨／送達不再扣實體或保留庫存。全域requestId核對actor／order／action／原version／trim後note，重送原event＋目前狀態，不倒退。員工啟用／權限寫入前重新核對及鎖定，再owner→order；進度／版本／server貨件號／時間／audit同transaction，記錄失敗回滾。普通員工可讀，店長才可模擬出貨／送達；顧客只讀本人配送安全投影，不含員工或內部說明。feature預設關閉，mock不是實際物流；不自動通知他人。未知回應同操作重試，重整先查配送与audit；退款／退貨／待核對處理另里程碑。


## 模擬退款與核對（2026-10-07）

V9不改V1–V8；僅未出貨全額mock退款。PAID／UNFULFILLED且無未處理review才ORDER_REFUND；stock.quantity回補、reserved不變、RETURN、REFUNDED／closedAt／version與audit同transaction，最多回補一次。REVIEW_REFUND只處理原成功異常通知、保留付款及通知、不改stock或訂單／配送status，只version+1；未處理review以退款ledger判斷，不能刪原證據。啟用員工／授權→owner→order→payment→sorted商品庫存鎖，與出貨競爭只一方成功。兩種退款共用全域requestId、payment唯一、actor／目標／版本／trim note核對；稽核失敗或容量超限全部回滾。顧客只本人安全退款投影，普通員工可讀、店長可操作；預設false，不轉帳、不傳訊。已出貨退貨／部分退款另階段，正式政策待核定。

庫存調整與退款統一員工／grant→product／inventory順序；不可先鎖product再等audit actor FK，避免與退款反向等待。庫存actor也寫入前DB重查啟用／inventory.adjust授權，username取DB。


## 已出貨整筆退貨演練（2026-10-07）

V10新增return申請／audit與已出貨退款guard，V1–V9不可改。每order一次整筆申請，拒絕不重申請；REQUESTED／RECEIVED凍結配送。驗收必須明確RESTOCK或NO_RESTOCK、不動stock；退款才按處置回補一次，NO_RESTOCK不寫stock或RETURN。退貨state／兩種audit／order version與退款stock同transaction、故障全回滾；保留原付款／通知與shipping進度。actor即時授權→owner→order→payment→sorted庫存；原ID原body重送、版本冲突先讀。顧客安全投影不含staff note／actor。MOCK_RETURNS_ENABLED預設false，退貨退款還需MOCK_REFUNDS_ENABLED；真實政策／部分退貨與寄送另階段，不能宣稱實體驗收或轉帳完成。


## 商品主圖／物件清理（2026-10-07）

V11只新增，V1–V10不改。DRAFT才能上傳／替換／移除一張主圖；商品version與image audit同SQL交易，actor即時啟用與grant鎖定再product。來源bytes SHA／alt／actor／目標／原version核對全域UUID；未知回應同File／UUID重試、ACK後讀取失敗只核對。S3私有bucket／不可變隨機key，不暴露objectKey／憑證、不信檔名，PNG／JPEG先驗尺寸再重編碼。跨SQL／S3以獨立已提交cleanup保留＋主交易queue鎖處理失敗／程序中斷，不能宣稱分散式原子。worker寬限10分鐘、active引用不刪、失敗留queue重試；只測自己可拋棄DB／bucket。下架／非目前主圖公開404，同源後端讀取no-store；舊檔案／avatar入口停用，新員工頭像另階段。新私有env需images initializer產生S3設定，不打印keys。

## 員工存取治理（2026-10-07）

V12後治理寫入使用singleton guard→ID排序全員工→role/grant→audit鎖順序，不反向等actor；業務仍actor/grant→資源。治理寫入需8個access.*完整能力，修改後至少保留一位啟用完整管理員。版本、指派、auth_version與audit同transaction；同UUID原actor／target／內容重播，不回密碼／指紋，不接受自訂URL／method權限。停用再啟用不可復活舊session；角色授權變更要撤銷指派員工session，純metadata不用。無API刪歷史，legacy治理寫入410；只有部分治理能力不能升權。V12不能改checksum，原DB不自動baseline／clean。


## 主檔與商品參照（2026-10-07）

V13不能改checksum；品牌／作品／分類獨立UUID與FK，商品brand/category IDs必填、work選填。V13保留歷史字串與商品版本，作品不猜；原DB先preflight／備份／隔離還原，不baseline／clean。legacy名稱只能解析既有主檔，不得旁路masters.create。主檔與商品四類寫入actor/grant→master guard→product；停用READ_COMMITTED核對PUBLISHED引用，與上架互斥。原關聯停用可留草稿，禁止新指派／上架；改名用live讀取，不重寫訂單快照。版本／UUID／稽核同交易，unknown ACK原操作重試；409重讀後明確編輯，不自動覆寫。無刪主檔／audit API。


## 通知 outbox（2026-10-07）
V14不可改checksum。業務事件以MANDATORY同交易保存六欄安全快照，無收件個資／note，業務重送不重複且不補發歷史。只有免費本地MOCK，不寄送真人；capture不作I/O，mock／worker各自預設false。actor/grant→queue→全域command reservation，先保留命令才独立mock接受；queue／version／audit同交易，provider receipt独立提交且無queueFK防反向等待，未知結果固定delivery key核對。五次退避後DEAD，人工重排保留累計及audit；worker每筆短交易SKIP LOCKED、SYSTEM audit，重啟依DB。不可宣稱exactly-once真送達；真adapter另設有界timeout、lease/fencing與reconcile，詳見NOTIFICATIONS_API。前端未知ACK同UUID/body、ACK後GET只重讀、409保留原因重開詳情。


## 預購批次基礎（2026-10-07）
V15不可改checksum；批次固定product／SKU，一批次獨立配額、deadline及預估arrival，DRAFT→OPEN→CLOSED，不重開／不刪歷史。僅草稿可改條件，開放鎖定報價及名稱快照；公開只已開放且目前上架啟用SKU，期限到讀EXPIRED、不暗改稽核。actor/grant→master guard→product→batch與商品鎖順序一致，全域UUID／版本／批次audit同交易，unknown ACK原操作重試；公開無內部原因／fingerprint。V15僅提供批次與查詢；V16訂單／held及orderingEnabled規則見下節。批次管理本身不動sku_inventory，不能把批次或保留完成說成預購收款／履約完成；後續付款／退款與通知合約見PREORDERS_API。


## 預購訂單與配額（2026-10-07）
V16只新增，不改V1–V15。獨立preorder_order／event，不能走現貨customer_order／付款／履約。一單一批次、1–100件，報價／收件／批次条件服務端快照；owner只Principal，同customer的PLACE／CANCEL共用UUID命令空間，核對target／原body，重播目前狀態不復活終態。placement customer→master guard→product→batch；release customer→batch→order，兩者先customer、batch在order之前，不能反向。held／batch version、order、不可變quota event與六欄安全outbox同transaction，故障全回滾，不動sku_inventory或cart。期限DBnow與30分鐘／deadline較早者，讀不暗寫；排程每筆短transaction、disabled customer亦可expiry。取消／到期最多釋放一次，截單不自動關訂單。outbox兩種parent恰一、原V14payload保留，filter/orderId投影沿用。V17付款退款見下節；到貨尚未做。前端unknown ACK保留原UUID/body凍結、ACK後GET僅讀、409重讀後明確操作；pending頁面記憶體，重整先本人清單核對。


## 預購模擬付款與退款（2026-10-07）
V17不可改checksum，V1–V16 bytes不變。獨立preorder money domain，僅重用stateless HMAC gateway，不套現貨PaymentService／FulfillmentService。customer→batch→order→payment；成功held→paid／PAID／PAY ledger／safe outbox同交易、不動physical或cart。逾時先expiry，晚到成功REVIEW_REQUIRED保留原成功證據、不復活終態或占回配額。start為customer內獨立UUID空間、event全域，未知ACK原操作重試；精確POST preorder callback免CSRF且需HMAC／5分鐘窗口，其他POST仍Cookie／CSRF。每單最多20意圖。退款live actor/grant→customer→batch→order→payment；全域UUID／payment唯一，ORDER_REFUND僅PAID未到貨未出貨無review，paid释放一次，REVIEW_REFUND只version與audit／outbox、不動quota或state。兩者全交易、故障回滾；顧客safe projection無staff note，普通staff唯讀。mock預設false，不實際收款／轉帳。到貨／分配／配送仍另里程碑，arrival／fulfillment欄位只是退款guard。


## 預購到貨與履約（2026-10-07）
V18發布後不可改checksum；獨立received/allocated/shipped supply，0≤shipped≤allocated≤received≤capacity，不借現貨stock service／sku_inventory。整筆分配僅PAID、無review、足夠到貨、未分配；allocatedQuantity=order.quantity，ARRIVED後既有未到貨退款拒絕。actor/live grant→customer→batch→order；receive只actor/grant→batch，不反向等customer。四類履約共用新全域UUID／order action唯一，原actor/target/action/version/trim note/receipt qty核對；供應／order版本／audit／safe outbox同交易、故障回滾，與refund競爭僅一方成功。SHIP／DELIVER預設mock false，原貨件與付款保留，不建立真物流；晚到review亦阻擋已分配出貨／已出貨送達。顧客只有本人safe progress無actor/note，普通staff唯讀。unknown ACK同body凍結、ACK後GET僅讀、409保留明確重開。原四項整筆預購MVP範圍完成後主線App；延期／訂金尾款／部分退款屬選擇性擴充，不能默默加為MVP必要條件。
