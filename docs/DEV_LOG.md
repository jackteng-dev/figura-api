> 開發紀錄：原 README 的完整里程碑紀錄（保留歷史，不再更新）。最新說明見 [README](../README.md)。

# ELPSY Shop — 後端

正式維護 A 版：Spring Boot 3.2.1、Java release 17、MyBatis、MySQL、Redis，搭配 [Shop_back](https://github.com/jackteng-dev/Shop_back) 的 Vue／TypeScript 前端。產品為公仔／收藏品單店，先現貨、再預購、最後顧客 App。

目前完成員工／顧客身分、商品／SKU與庫存稽核、購物車／本人地址、訂單快照與30分鐘保留，商品主圖（SeaweedFS）已可上傳、替換、移除與公開顯示，以及**免費模擬付款、冪等通知、售出扣實體庫存、員工模擬履約、未出貨退款、已出貨整筆退貨驗收與異常付款核對**。顧客可在 `/shop/orders` 測試付款成功／失敗與查看狀態；不會實際收款。預購到貨與模擬履約已完成；顧客App與正式部署仍待完成。部分退款等屬後續擴充。

先讀 [AGENTS](../AGENTS.md)、[最新交接](HANDOFF.md)、[路線圖](ROADMAP.md)。啟動與命令見 [新機／隔離展示](NEW_MACHINE_SETUP.md)；原資料、真機、商家服務見 [本地待補](CLOUD_LOCAL_FOLLOWUP.md)。

```sh
mvn package
```

普通測試不連 MySQL，真實 Redis 測試需明確啟用且指向隔離服務。本輪完整測試／HTTP 聯測證據見 [verification](verification/baseline-2026-10-07.json)。私有資料不納入 Git；demo SQL 不適用原 DB，沒有硬編碼可用展示密碼。兩倉庫獨立 main、測試、HANDOFF 與提交。

顧客會員里程碑（2026-10-07）：`/shop` 提供註冊、登入、本人暱稱編輯、登出，獨立顧客表／Cookie／realm／Redis session；正式 JAR 與真實 MySQL／Redis 驗證見 [顧客紀錄](verification/customer-identity-2026-10-07.json)。商品里程碑另見下方，尚未有下單，原 /api/user/* 已停用，新 demo 改 Flyway 初始化，不再手動匯入舊 SQL。

商品里程碑1B：`/api/admin/products` 草稿與 SKU、上架／下架／封存，GET `/api/catalog/products` 公開列表／詳情。MySQL交易回滾、全域 SKU唯一、版本競爭、URL＋method權限。demo新增Flyway V3，不改V1/V2；[API合約](CATALOG_API.md)、[本輪驗證](verification/catalog-2026-10-07.json)。價格為 TWD整數元；SKU庫存另見下一里程碑；尚未包含圖片上傳／結帳。

庫存里程碑1C：SKU數量、獨立版本、入庫／盤點／損耗與本人異動稽核；MySQL列鎖、非負CHECK、requestId冪等重送、紀錄失敗整筆回滾。公開SKU只回available；[API合約](INVENTORY_API.md)、[本輪驗證](verification/inventory-2026-10-07.json)。V4既有SKU回填0，不推測真實庫存；沒有訂單保留與售出扣庫。

交易里程碑2A：顧客 `/shop/cart`／`/shop/addresses`，本人購物車與地址持久保存、整車／地址版本防覆寫、伺服器重新計價與下架／缺貨核對；購物車不保留庫存，下單／結帳未開放。demo新增Flyway V5，原V1–V4不改；[本輪證據](verification/shopping-2026-10-07.json)。後續圖片儲存已選定 **SeaweedFS＋通用S3 SDK**，本輪僅記錄選型，尚未實作圖片上傳或啟動物件儲存。

交易里程碑2B（2026-10-07）：現貨訂單價格／商品／收件快照、30分鐘庫存保留、冪等下單、本人訂單與取消／逾時釋放已完成。顧客 `/shop/cart` 確認下單、`/shop/orders` 核對與取消；庫存區分實體／保留／可售。配送費0為展示設定，付款／履約／退款未接；SeaweedFS圖片仍待下一獨立里程碑。新版驗證以 [最新交接](HANDOFF.md) 與 [本輪證據](verification/orders-2026-10-07.json) 為準，以上段落為歷史里程碑。


交易里程碑2C（2026-10-07）：付款意圖金額由伺服器快照建立，私有HMAC簽章通知、eventId冪等、付款／取消／逾時競爭、PAID與CONSUME交易回滾完成。畫面支援遺失回應後同操作重試，取消後晚到成功顯示待核對。demo新增V7、V1–V6不改；模擬預設關閉，隔離demo需私人簽章金鑰。驗證與目前限制以 [最新交接](HANDOFF.md) 與 [本輪證據](verification/payments-2026-10-07.json) 為準；前述里程碑段落保留當時狀態，不能作最新完成度。


營運里程碑3A（2026-10-07）：員工 `/admin/orders` 摘要／篩選／分頁、收件詳情，店長模擬出貨與送達、前後版本與本人稽核，顧客 `/shop/orders` 配送進度已完成。付款／配送狀態分開、出貨不再扣庫；未知回應沿同操作重試、舊版次拒絕覆寫。demo V8保留V1–V7，MOCK_FULFILLMENT_ENABLED預設關閉，沒有真實物流或寄送通知。最新完成度與測試以 [交接](HANDOFF.md) 與 [履約證據](verification/fulfillment-2026-10-07.json) 為準；以上較早里程碑保留當時狀態。


營運里程碑3B（2026-10-07）：未出貨全額模擬退款與庫存一次回補、異常付款核對模擬退款（不動庫存）、原通知與員工退款audit、顧客本人退款狀態已完成。V9保留V1–V8，MOCK_REFUNDS_ENABLED預設false，不實際轉帳；已出貨退貨與部分退款未做。最新驗證以[退款證據](verification/refunds-2026-10-07.json)及[交接](HANDOFF.md)為準。下一步可做已出貨退貨演練，再補SeaweedFS圖片／完整權限管理／通知outbox。


營運里程碑3C（2026-10-07）：已出貨整筆退貨申請、店長模擬收貨驗收、可重新入庫／不回補處置、全額模擬退貨退款與兩端進度已完成。退款保留原配送／付款紀錄；可入庫才回補一次，驗收不動庫存。V10保留V1–V9，MOCK_RETURNS_ENABLED預設false，不寄送或轉帳。每order限一次申請、拒絕後不重申請只作演練；部分退貨／費用／正式政策仍待核定。最新完成度與測試見[交接](HANDOFF.md)及[退貨證據](verification/returns-2026-10-07.json)。下一步補SeaweedFS圖片，再做主檔／完整RBAC／通知outbox、預購及App。


商品主圖里程碑（2026-10-07）：免費自架SeaweedFS＋通用S3 SDK已接入，支援一件商品一張主圖、草稿上傳／替換／移除、稽核與商品版次、私有物件讀取及顧客公開顯示。PNG／JPEG驗證與重新編碼，舊圖／失敗暫存使用持久清理佇列；不是SQL＋S3分散式原子交易。舊MinIO helper／依賴已移除，舊avatar／任意本地檔入口停用，沒有移轉舊素材、正式部署或接員工頭像。多圖／SKU圖、縮圖、正式儲存維護另階段。最新結果見[交接](HANDOFF.md)與[圖片證據](verification/images-2026-10-07.json)，下一步主檔／完整權限管理。

存取治理里程碑（2026-10-07）：實際員工／角色／固定權限／稽核管理頁，啟停與角色指派、版本與UUID重送、最後管理員保護及舊session永久撤銷已完成。V12保留V1–V11；升級後員工需重新登入。安全條件與限制見[存取治理合約](STAFF_ACCESS_API.md)，最新驗證以[交接](HANDOFF.md)為準；主檔進度見下列V13。

品牌／作品／分類與商品ID關聯見 [MASTERS_API](MASTERS_API.md)，普通員工唯讀主檔、店長可管理，改名不改寫歷史訂單。

通知里程碑（2026-10-07）：V14 交易 outbox、13類訂單事件、免費本地 mock 接受／未知結果去重／退避重試／DEAD人工重排、真後台權限與唯讀audit已實作；mock／worker預設關閉，未寄送真人。原資料與外部transport驗收待本地。[合約](NOTIFICATIONS_API.md)，最新測試與下一步以[HANDOFF](HANDOFF.md)為準。


預購第一里程碑（2026-10-07）：批次草稿管理、固定 SKU 與獨立供應配額、截止與預估到貨區間、開放／截單、版本與稽核、顧客 `/shop/preorders` 匿名查詢已實作。開放後固定條件，商品下架隱藏批次；不改現貨庫存。第二里程碑已加入顧客預購下單、獨立配額保留、本人訂單／取消、逾時釋放與同交易 outbox；下一步整筆 mock 付款／退款，之後到貨履約；見 [預購合約](PREORDERS_API.md) 及最新 [HANDOFF](HANDOFF.md)。


預購保留回歸：後端`python scripts/smoke_preorder_orders.py --base-url http://127.0.0.1:19092/ELPSY/api --output work/preorder-orders-20261007/http-preorder_orders.json`；前端`node scripts/smoke_preorder_orders_browser.cjs`（同既有私有demo／Playwright環境，SHOP_FRONTEND_URL指定production preview）。只允許loopback合成資料，排程必須啟用；不測真人寄訊或收款。最新完整驗證見HANDOFF與`docs/verification/preorder-orders-2026-10-07.json`。


預購里程碑3（2026-10-07）：V17獨立整筆mock付款、signed callback與冪等、held→paid、不提前扣現貨、晚到成功待核對、未到貨整筆mock退款及核對退款已完成。顧客 `/shop/preorder-orders` 付款，店長 `/admin/preorder-orders` 查詢與退款，普通員工唯讀。mock不實際收款或轉帳；到貨分配與預購出貨、延期／訂金尾款、App仍待完成。最新合約見[PREORDERS_API](PREORDERS_API.md)，驗證與限制見[交接](HANDOFF.md)及[本輪證據](verification/preorder-money-2026-10-07.json)；以上舊段落保留歷史狀態。


預購里程碑4（2026-10-07）：V18到貨登記／整筆分配、付款與review及數量守門、分配對退款競爭、模擬出貨／送達、不可變稽核與safe outbox及本人進度已完成。預購供應獨立，不提前或重複扣現貨；原四項整筆預購MVP範圍完成，下一條主線顧客App。到貨須人工核對實物，mock配送沒有真物流。最新[交接](HANDOFF.md)、[合約](PREORDERS_API.md)及[驗證](verification/preorder-fulfillment-2026-10-07.json)為準；歷史段落保留当時狀態。
