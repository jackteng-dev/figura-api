# 雲端完成與本地待補

更新：2026-10-07（Asia/Taipei）。最終在使用者本機部署，雲端只作開發／隔離演練；不要求現在下載到個人電腦。

## 本輪雲端範圍

A 版啟動／正式 build／員工與顧客 Cookie 身分、本人暱稱隔離與真實 MySQL／Redis 聯測，商品／SKU 權限與公開瀏覽，SKU庫存與異動稽核，顧客購物車／本人地址，demo Flyway V1／V2／V3／V4／V5；詳細證據見 HANDOFF。使用免費工具和合成帳號，未連原 DB、物件儲存、真實金流、郵件或 LINE。

## 到可用本地環境再補

| 項目 | 需要資料／環境 | 驗收方式 |
| --- | --- | --- |
| 新機啟動 | Node、完整 JDK、Maven、Docker、私有設定 | NEW_MACHINE_SETUP；重新初始化空 demo 並重跑測試，確認本地 proxy／CA |
| 原 DB 移轉 | 使用者原 SQL schema／版本、備份副本，不能提供公開帳密 | 先隔離還原，盤點 permissions_backend 等差異；制定 migration／回退，不直接跑 demo SQL |
| 歷史私有資訊 | 原版本曾有寫死連線／JWT 金鑰 | 由帳號管理者檢查、輪替實際仍使用的舊憑證；本輪沒有連原服務或更改原帳號 |
| 檔案與品牌素材 | 自有／授權圖片、SeaweedFS bucket 與私有憑證 | 雲端隔離SeaweedFS主圖已完成；自有素材移轉、正式bucket維護與備份隔離還原待本地 |
| HTTPS 與多作品部署 | 本機反向代理、網域／TLS、啟動管理、資料 volume | Secure Cookie、同源 proxy、瀏覽器完整流程；現在的 Vite preview 不是正式部署 |
| 顧客 App | Shop_app Flutter 第一版；Android／iOS 裝置、macOS／Xcode與簽名 | Android APK compile已驗，真機 secure storage／背景／斷網／訂單與 iOS build待驗；推播／商店上架另階段 |
| 金流／配送 | 商家資格、sandbox／正式憑證、退款政策 | 先 sandbox，再少量實際驗收；mock 訂單與 callback 不算收款 |
| 通知 | SMTP／推播／LINE 的私人設定與免費方案配額 | outbox／重試、實際送達與超額處理；沒有寄訊或購買服務 |
| 正式資料保存 | 商品、訂單、檔案與服務清單 | 備份及隔離還原演練、權限、監控與回復程序 |

顧客註冊／商品／訂單等後續程式仍可在 Codex Cloud 分階段完成，並非都需要等本地。真正外部服務、裝置與原資料驗收留在此清單；目前13個demo表（不含history）仍非完整交易schema；庫存調整已完成，訂單保留與交易另階段。

## 本輪新增本地待補

原 DB／既有無 Flyway history 的 M0 demo 不自動接管；需備份／隔離還原後規劃 migration 或明確 baseline。顧客舊資料遷移、信箱驗證、密碼重設、登入／註冊限流、隱私資料與稽核需後續驗收；本輪只使用合成會員與暱稱。正式部署前另排依賴維護：Flyway 9.22 對 MySQL 8.4 有支援版本提示，本輪實測 migration 通過但未升級整個工具鏈。

## 商品階段本地待補

自有或授權商品圖片、品牌／作品／系列分類資料與原 SKU 清理待本地驗收。品牌／作品／分類主檔與商品固定ID關聯、私有SeaweedFS主圖、庫存與購物車／地址、訂單價格快照／保留庫存以及mock付款／履約／退款／退貨已在雲端隔離環境實作；正式素材、原資料清理／移轉與真金流物流仍待本地。訂單使用SKU ID、伺服器快照與庫存交易，不信任頁面價格。

## 庫存階段本地待補

需實際SKU／盤點資料、員工職責及庫存調整簽核政策。雲端已用合成資料驗證增減／版本競爭／重送／稽核回滾；沒有套用真實盤點或從原資料推測數量。正式上線再補最小DB授權、稽核保存／匯出、備份還原與壓測；ledger無API刪改但不等於DB管理者不能改。訂單庫存保留／扣減／取消釋放仍可在雲端繼續開發。

## 購物車／地址與 SeaweedFS 待補

雲端只用合成收件資料，沒有真實個資／配送。現有地址限定台灣欄位、最多10，尚未檢核郵遞區號／行政區對應、預設地址或訂單收件快照。正式上線需核定隱私保存／刪除策略及運費／配送區域；地址刪除已清空內容並留UUID墓碑防止復活。購物車不保留庫存，真正下單／價格快照／庫存保留仍可先在雲端開發。

使用者決定後續採免費自架 SeaweedFS＋S3 API，單機開始，各專案獨立bucket／憑證。雲端可先做圖片程式與隔離S3實測；本地再驗資料volume持久化、實際素材、網域／TLS、預簽名網址可達性、備份與隔離還原。當前沒有啟動SeaweedFS、移轉圖片或替換舊MinIO helper／SDK，不要把選型決定當部署完成。

## 里程碑2B：訂單與保留庫存

雲端已實作及驗證本人快照／冪等下單、30分鐘保留、取消／逾時釋放。原資料庫先備份與隔離還原再規劃 V6；既有 quantity仍實體，reserved初值0，不能猜測真實未付款訂單。本機確認MySQL／JDBC台北timezone、排程expiry-enabled與重啟補做、持久volume／備份／告警；不要把localhost preview視為正式HTTPS。實際配送費、收件個資保存／清除、付款adapter／商家sandbox、實體扣庫、履約／退款與裝置驗收仍未做。SeaweedFS圖片依原待補順序，不宣稱已切換。


## 里程碑2C：免費模擬付款

雲端隔離MySQL／Redis已驗證付款意圖、signed callback、重送／衝突、付款對取消與到期競爭、一次CONSUME與PAID、ledger／多品項故障回滾。demo schema已V7；以上舊段落為各階段當時狀態。沒有連任何實際金流／商家帳戶、信用卡、真實個資或外部通知。

本機先備份／隔離還原與規劃原DB V7；確認timezone、原未付款資料及庫存保留，不自行推定已收款。私人MOCK_PAYMENT_WEBHOOK_SECRET不進Git或VITE_*，外部服務需金鑰管理／輪替、HTTPS、限流、request body限制與重試監控。非demo預設關閉mock，不把模擬endpoint公開作正式支付。金流sandbox／實際資格、人工核對／退款政策與流程、履約物流、備份／監控／壓測仍待實作及本機驗收。待核對目前只記錄／凍結付款，沒有核對／退費管理入口。


## 里程碑3A：員工訂單與模擬履約

雲端新增V8与orders.*真實權限、PAID保留／配送獨立狀態、員工出貨／送達ledger、顧客安全投影与UI。新空專用demo以V7正式JAR先建已付款訂單，再驗證V8升級保留原金額／收件／paidAt／庫存；沒有操作原DB、真實物流或寄送任何訊息。

本機先備份、隔離還原與規劃V8，核定可查收件資料與可履約的員工職責；demo STAFF可讀、ADMIN可操作只作演練政策，正式RBAC編輯器尚未完成。MOCK_FULFILLMENT_ENABLED非demo預設關閉，不把server產生MOCK-UUID視為物流追蹤碼。實際物流adapter／回報驗簽、配送費與區域、退貨與退款／待核對政策、通知outbox、個資与audit保存／匯出、backup／HTTPS／告警／大資料量分頁與壓測仍需後續實作或本機驗收。


3B更新：已在隔離雲端完成未出貨全額模擬退款／庫存一次回補及異常付款核對演練。真實退款金流憑證、退款期限／手續費／部分退款、已出貨退貨驗收／重新入庫、收件與內部note保存清除政策、原DB V9隔離升級、HTTPS／監控與備份仍須本地或商家環境驗收；mock不表示實際退費。


3C更新：已出貨整筆退貨申請／模擬驗收／全額mock退款與依處置一次回補已在隔離雲端完成，保留原shipping與payment；不表示收到實體商品或真實退款。正式退貨資格／期限／拒絕後再申請、部分退貨／手續費／配送費、實體驗收與回庫簽核、物流逆向adapter／金流refund憑證、個資與內部note保存政策需核定。原DB先備份與隔離還原再規劃V10，guard與既有退款／shipping資料不得推定或自動repair。HTTPS、持久化／備份／監控與壓測另验；SeaweedFS圖片仍未實作，不啟動舊MinIO作新方案。


商品主圖更新：已在隔離雲端接真實SeaweedFS＋S3 SDK，私有bucket及後端同源內容、上傳／替換／移除、持久失敗清理；舊MinIO helper／依賴已移除。沒有移轉原圖或接員工頭像。正式先核定素材授權／圖檔配額、SeaweedFS版本維護／最小憑證／HTTPS、原DB V11備份與隔離升級、filer metadata與volume共同備份／隔離還原、清理佇列監控／失敗告警、quota／壓測／多機故障與災難恢復；單機container restart不等於完整backup還原或容錯。多圖／SKU圖／縮圖／CDN／presign直傳與舊avatar存取另階段。以上較早段落保留當時狀態，最新以HANDOFF為準。

## 員工治理本地待補（2026-10-07）

原員工／role/grant資料先備份與隔離升級V12，核對至少一位啟用完整管理員；升級前員工JWT需重新登入，顧客不變。演練SQL＋Seaweedmetadata/volume備份與還原。正式staff密碼重設／MFA／安全私下交付、session金鑰輪替／audit指紋兼容、稽核保留與防竄改、治理鎖高頻負載／等待監控、搜尋與大名單選擇另補；此輪單店少量員工，未做真實人員上線或寄送邀請。


## V13主檔正式移轉（待本地環境）

- 先備份原資料／私有S3、在獨立DB與bucket還原；不要把demo migration直接套原庫。
- 列出brand/category空白、前後空白、大小寫／重音異名、重複或人工合併對照。確定保留或合併策略；作品由人工核對，不以商品名稱猜。
- 以獨立新migration規劃原DB導入；核對product/SKU/inventory/歷史訂單與圖片SHA、FK與版本。驗收失敗以已驗證備份隔離回復，不repair／清history繞過。
- 本輪雲端只驗合成V12→V13。階層分類／多品牌作品關係／大量搜尋選項是後續產品範圍，不是已完成項。

## V14 通知正式驗收（待可用本地環境）
- 雲端完成免費本地MOCK与交易佇列，沒有真人收件服務。確認接收者同意／偏好、最小資料、保留期限、失敗告警與人工核對。
- 在原DB先備份、隔離還原、核對V1–V13歷史再規劃正式V14；雲端合成升級不替代原資料移轉。通知queue與provider receipt及audit需一起備份／還原，不能還原後自動補發。
- 真Email／LINE／推播adapter先選免費方案與quota，另加有界timeout、claim／lease／fencing、provider idempotency及unknown reconcile；SMTP無通用exactly-once。不要在SQL row lock交易內直接socket寄送。
- 真sandbox收件／DNS驗證、TLS／credentials、費率与限流、退信／送達回報、duplicate及重啟恢復待實際服務測試。未接付費服務；免費替代不可行再告知。
- 顧客通知中心／App推播、會員Email驗證／密碼重設另阶段，不把mock SENT作真人送達。


## 預購批次 V15 的本地／正式後續
批次管理／匿名查詢已實作；預購訂單、配額保留、整筆mock付款退款、到貨分配及出貨前核對待後續開發。正式供應商配額／真素材、預估到貨／延期／取消退款政策需實際商家資料；不把配額當現貨或保證交期。V15只新增批次／稽核／專用授權，舊DB需備份與隔離升級；本輪合成V14→V15驗證不取代正式還原演練。顧客App與真金流／物流／通知另階段。


## V16 預購訂單後續
隔離合成V15→V16升級只驗既有商品／現貨訂單／庫存／通知與批次保留，不替代原資料preflight／備份與隔離restore。原庫不baseline／clean／repair。實際商家仍需核定預購資格、報價、截止／到貨政策、取消退款及運費；本版零運費展示，保留30分鐘／截止較早且付款尚未開放。真金流、真通知、到貨及可履約量、HTTPS正式部署、App真機驗收另階段。


## V17 預購模擬付款／退款後續
隔離合成V16→V17保留既有現貨／預購快照與quota，不能替代原資料preflight／備份及隔離restore。HMAC私密不進VITE或Git，mock及refund預設false。正式商家金流／退款政策、取消期限／費用、到貨分配與可履約量、延期、部分退款、供應商配額、HTTPS及備份監控需後續驗收；目前arrival／fulfillment guard不代表到貨或物流已完成。不實際收款、轉帳或寄送訊息，顧客App另階段。


## V18 預購到貨與履約後續
本版完成到貨資料登記／整筆分配與mock配送，不代表商家實體驗收或真物流。供應數量與現貨分開，正式商家須核對到貨／短缺與預購庫存盤點政策；分配後退款與預購退貨另設計，不能直接使用現貨退款。原DB先preflight、備份與隔離restore，再规划V18，不baseline/clean/repair。四項整筆預購MVP完成後主線App；訂金尾款、延期、部分退款可依商家需求擴充。真provider／政策、HTTPS、會員安全及備份監控另正式驗收。

## 顧客 App 更新（2026-10-07）

Shop_app 已獨立建立並實作第一版購物／現貨與預購訂單流程，重用原後端与 V18 schema；mobile JSON Bearer realm/session與網站Cookie/CSRF分離。Android debug／unsigned release compile、Flutter測試與隔離後端流程見各倉庫HANDOFF；還沒有真機、iOS build、正式簽名與商店上架。下一步以裝置驗收為主，原整筆預購MVP完成範圍不增加。
