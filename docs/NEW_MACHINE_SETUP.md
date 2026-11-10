# 新機／隔離展示啟動

本輪在 Codex Cloud 實測 Node 24.19、npm 11.9、完整 JDK 21（編譯 release 17）、Maven 3.9、Docker MySQL 8.4、Redis 7。最終本機需重新驗收，不假設雲端資料自動帶回。

## 1. 私有環境與新空 DB

從本倉庫 main clone；讀 AGENTS、HANDOFF。以下命令在後端根目錄執行。Docker 容器也需遵守自己環境 proxy／CA；不要改其他作品容器或共用 DB。

```sh
python3 scripts/init_demo_env.py
docker compose --env-file work/demo.env -f docker-compose.demo.yml up -d
```

設定檔含隨機私有 root／DB／JWT／兩個不同展示密碼，產生器不覆蓋既有檔、檔權限 0600、不輸出密碼。Windows 需另外限制檔案 ACL。若已有 work/demo.env，沿用或自行管理，不重新產生假裝重設帳號。兩帳號為 shop_admin（展示店長）與 shop_staff（可讀商品與編輯草稿，不能上架）。

容器只綁 loopback 13306／17379，Compose 專用 MySQL volume 持久保存 demo；Redis 不持久，重啟會撤銷既有 session。用 `docker compose --env-file work/demo.env -f docker-compose.demo.yml ps` 確認 MySQL healthy。

**使用全新空 elpsy_shop_demo。** 本輪改由 demo profile 的 Flyway 自動執行 V1 員工身分、V2 顧客身分、V3 商品／SKU與穩定權限碼、V4 SKU庫存與異動稽核、V5購物車與本人地址、V6訂單保留、V7模擬付款，不再手動跑 scripts/demo-identity-schema.sql。SQL 在 classpath 使用 UTF-8；schema history 有 checksum。

migration 在任何寫入前核對資料庫名稱。預設非 demo 關閉 Flyway；不自動 baseline／clean。若是上一輪已手動建五表、沒有 history 的 demo，Flyway 會拒絕接管：先備份、隔離還原與規劃 baseline，或使用新的可拋棄 demo DB；不可為測試直接清原資料。原 DB schema 移轉另見本地待補。

demo 用戶初始化仍只在空 user 表建立兩個帳號，已有用戶就跳過；不可將新機設定檔當成密碼重設。

## 2. 後端測試與啟動

Spring 不會自動載入 .env。以下 Linux shell 先匯入自己產生的可信私有檔，PowerShell 請以程序環境變數提供同樣欄位。不要輸出環境或貼帳密。

```sh
set -a
. ./work/demo.env
set +a
SHOP_REDIS_INTEGRATION=true SHOP_REDIS_TEST_PORT=17379 SHOP_MYSQL_INTEGRATION=true mvn package
java -jar target/figura-api-1.0-SNAPSHOT.jar --spring.profiles.active=demo
```

普通 `mvn package` 不需要 DB；未啟用 SHOP_REDIS_INTEGRATION 時略過兩個真實 Redis 測試，未啟用 SHOP_MYSQL_INTEGRATION 時略過六個顧客、七個商品與十一個庫存、九個購物車／地址、十二個訂單、十三個付款真實 MySQL／Redis 整合測試（庫存升級fixture需SHOP_MIGRATION_FIXTURE_SKU；V4→V5另需SHOP_V5_FIXTURE_CUSTOMER，指向由舊正式JAR建立的本人資料）。完整驗證要先匯入私有 demo env、開隔離服務，第一次測試會自動 migration 與建立兩個展示員工。兩個員工 Redis 測試用 database 15；顧客整合用 Spring 設定的 database（本輪預設0），按隨機顧客 ID 清自己的 key，不 flushDB。請只指向演練 Redis，不能拿此命令測共用正式 Redis。

API 在 `http://127.0.0.1:19092/figura/api`。非 demo profile 不建立展示用戶，正式設定必須自行提供 DB、Redis、JWT_SECRET 與原 schema。後續圖片採 SeaweedFS；目前物件儲存未啟動，本輪不驗上傳。

顧客入口 `http://127.0.0.1:15176/shop`，自行註冊合成會員；`/shop/account` 編輯本人暱稱，員工入口仍 `/admin/login`。顧客與員工可同瀏覽器共存；顧客 Cookie 是 customerAccessToken／customerRefreshToken、獨立 customerDeviceId。兩種 JWT 新增 realm，舊員工 token 需重新登入，沒有改既有密碼。舊 /api/user/* 一律410，不再作公開會員入口。

Cookie 預設 HttpOnly、SameSite=Strict、Secure=true；私有 demo 檔明確 false 僅用於 loopback HTTP。正式同源 HTTPS 維持 Secure=true。除精確POST /api/payments/mock/callback改驗私有HMAC外，所有不安全 method 需要 CSRF：先 GET auth/csrf，再以 Cookie 與 X-XSRF-TOKEN 操作。access 15 分、refresh 7 天；rememberMe 控制 Cookie 持久性，不更改 token 到期。

## 3. 前端正式 build

另一終端進入 Shop_back，從前端 main 同步並讀自己的交接。

```sh
npm ci
npm run build
VITE_API_PROXY_TARGET=http://127.0.0.1:19092/figura npm run preview -- --host 127.0.0.1 --port 15176 --strictPort
```

開 `http://127.0.0.1:15176/admin/login`，使用自己 work/demo.env 的展示帳密。Vite dev／preview 同源 /api proxy 到後端；production dist 的正式部署需另外配置反向代理，不把 Vite preview 當正式伺服器。不要在 VITE_* 放私有值。

## 4. 重跑聯測

後端終端已匯入私有環境，執行：

```sh
python3 scripts/smoke_auth.py --output work/customer-20261007/http-staff.json
python3 scripts/smoke_customer.py
python3 scripts/smoke_catalog.py
python3 scripts/smoke_inventory.py
python3 scripts/smoke_shopping.py
```

顧客 HTTP smoke 會建立一個隨機合成帳號；browser smoke 另建一個，只在獨立可拋棄 demo 使用，腳本不刪使用者資料。真實 MySQL Maven 整合測試會清自己的顧客／Redis keys，保留展示員工。

腳本僅允許 loopback，檢查登入／CSRF／角色隔離／裝置登出／四請求 refresh 競爭／重播。結果摘要不含 token 或密碼。真正 MySQL 停用與撤權狀態演練的本輪證據另見 verification；不可對原 DB 任意改帳號驗證。

前端 browser smoke 用免費 Playwright 與 Chromium。可獨立安裝在忽略 work：

```sh
npm install --prefix work/browser-tools --no-save playwright
node work/browser-tools/node_modules/playwright/cli.js install chromium
```

將同樣 DEMO_ADMIN_PASSWORD／DEMO_STAFF_PASSWORD 私有環境提供給前端終端。示例 Linux（假設兩倉庫相鄰）：

```sh
set -a
. ../shop_bak_ap/work/demo.env
set +a
PLAYWRIGHT_MODULE_PATH="$PWD/work/browser-tools/node_modules/playwright" node scripts/smoke_customer_browser.cjs
PLAYWRIGHT_MODULE_PATH="$PWD/work/browser-tools/node_modules/playwright" node scripts/smoke_browser.cjs
PLAYWRIGHT_MODULE_PATH="$PWD/work/browser-tools/node_modules/playwright" node scripts/smoke_catalog_browser.cjs
PLAYWRIGHT_MODULE_PATH="$PWD/work/browser-tools/node_modules/playwright" node scripts/smoke_inventory_browser.cjs
PLAYWRIGHT_MODULE_PATH="$PWD/work/browser-tools/node_modules/playwright" node scripts/smoke_shopping_browser.cjs
```

有系統 Chromium 可提供 PLAYWRIGHT_CHROMIUM_PATH；PLAYWRIGHT_MODULE_PATH 使用絕對路徑。

SHOP_FRONTEND_URL 可改 loopback URL，預設15176；測試阻擋外部網路資源，只驗本作品。顧客腳本驗證註冊、錯密碼、本人暱稱持久化／文字轉義、refresh、員工共存／獨立登出、390px 註冊頁與 JS 錯誤；員工腳本仍是管理頁占位路由與真實登入。商品腳本另驗多 SKU 草稿、權限、上架／下架、公開投影、版本衝突、封存與390px商品頁。先在沒有上架商品的可拋棄 demo 跑它；只封存自建商品，不刪用戶資料。交易仍未完成。

## 5. 收尾／保存

Ctrl+C 停止自己啟動的 Java／preview。`docker compose --env-file work/demo.env -f docker-compose.demo.yml down` 停止本作品服務、保留 volume。確定不要隔離 demo 資料才自行移除專用 volume；本文件不提供共用容器或全部 volume 清除命令。私有檔與依賴留 work，程式／文件在兩倉庫各自測試、commit、push main。

## 商品入口

顧客 `/shop/products`、`/shop/products/:id` 匿名可讀已上架商品與啟用 SKU；員工 `/admin/products` 由 catalog.read.list 權限碼建立路由。shop_staff 可建／編輯草稿，shop_admin 可上架／下架。已上架先下架才能修改。封存不可復原，保留資料；SKU 編碼正規化大寫且全域不可重用，既有編碼不可改。API與版本合約見 CATALOG_API.md。沒有種入假商品，第一次列表為空。

## 庫存入口與測試

選已儲存商品後可見「SKU庫存與異動」。shop_staff可讀、shop_admin可調整；入庫正數、損耗負數、盤點可正可負，需說明。新SKU均零庫存，不能透過商品表單指定數量。公開頁只顯示有／無庫存，沒有訂單保留。未知網路結果可在原頁重試同一操作；離開頁面前先核對異動。

本輪升級fixture由上一版V3正式JAR在新空demo建立一件草稿／SKU，再由V4package migration回填0，透過SHOP_MIGRATION_FIXTURE_SKU指向該SKU；不設定此marker就略過該1項，不能宣稱重跑了升級情境。其他10個庫存測試可直接在V4 demo跑。稽核失敗測試以直接transactional service＋不存在actor外鍵模擬真實MySQL插入失敗，不需要SUPER／trigger或降低DB限制。庫存整合只封存自建商品，稽核保留到拋棄專用DB；其他商品整合仍只刪自己無稽核的fixture。

## 購物車／地址入口與升級

`/shop/cart` 管理本人數量／移除與目前可用小計；`/shop/addresses` 儲存台灣收件欄位。合約見 SHOPPING_API.md。版本或網路結果不明，先重新核對；不自動加量重試、不保留庫存。新HTTP／browser smoke限定可拋棄demo，建立2名合成會員與商品、封存產品、不刪共享稽核或正式資料，輸出在work/shopping-20261007。

本輪V4→V5由起始main正式JAR建立customer／SKU，再用新版package升級；兩個fixture marker由私有runtime提供，完整62項。普通完整DB驗證沒有marker則60通過／2項升級fixture略過；不能以重新指向任意新資料宣稱真正升級。V1–V4不改checksum，失敗演練只重建本輪專用空DB，沒有Flyway repair／baseline／clean原資料。

## 里程碑2B：訂單

以最新main與HANDOFF為準，demo Flyway已包含V6，保留V1–V5。完整DB測試需原有SHOP_MYSQL_INTEGRATION／SHOP_REDIS_INTEGRATION；不設歷史fixture marker會略過兩項舊升級演練。新HTTP `python3 scripts/smoke_orders.py`、browser `node scripts/smoke_orders_browser.cjs`。Browser在自己的Docker MySQL合成UUID訂單設定過去期限，需提供後端demo.env及DB_PASSWORD；JAR用 `--shop.orders.expiry-delay-ms=1000`僅縮短測試掃描間隔，產品期限仍30分钟。

顧客加入款式、建立本人地址後，在購物車核對金額／地址下單，再到我的訂單取消。版次與價格變動先重讀；未知回應同操作重試／查本人訂單，離開頁面前先核對。正式啟動必須做原DB隔離升級、timezone與expiry-enabled核對，金流與物流另行驗收。


## 里程碑2C：模擬付款

最新demo Flyway包含V1–V7，舊SQL不改。新後端私有設定產生器已提供mock金鑰；已有work/demo.env先於後端根目錄跑 `python3 scripts/init_mock_payment_env.py`，只追加缺少的MOCK_PAYMENTS_ENABLED／MOCK_PAYMENT_WEBHOOK_SECRET，不覆蓋既有值。啟用mock需至少32字元私有金鑰；不放VITE_*，不輸出環境內容。關閉時顧客面板顯示未啟用，callback／模擬建立404。

後端完整DB package包含13項新付款整合与2項gateway單元測試；本輪89項中87通過、2歷史升級marker略過。普通無DB驗證不等於跑過真實付款。先等待正式JAR的auth/csrf回200，再執行 `python3 scripts/smoke_payments.py`；前端匯入相同私有demo環境，執行 `PLAYWRIGHT_MODULE_PATH=/絕對路徑/playwright node scripts/smoke_payments_browser.cjs`。使用隔離可拋棄demo、正式dist與JAR；付款腳本為真正資料寫入，不用假owner／金額／庫存。原始結果／截圖在忽略work/payments-20261007。

顧客登入並下單後，到 `/shop/orders` 建立模擬付款、測試成功或失敗；明示不實收。回應未知就同操作重試／核對，重整後先讀本人訂單與付款。成功訂單不能直接取消；取消／到期後晚到成功只待核對；核對／退款未提供。不要在正式資料或原DB跑smoke。實際金流与HTTPS／物流需另驗收。


## 里程碑3A：員工訂單與模擬配送

最新demo包含V1–V8，V1–V7不改。新的後端設定產生器已包含MOCK_FULFILLMENT_ENABLED=true；已有可信私有work/demo.env，在後端根目錄跑 `python3 scripts/init_fulfillment_env.py`，只補缺開關，不覆蓋已有true／false或憑證。Spring不自動載入env，仍以程序環境提供；非demo／未指定開關預設關閉，模擬寫入404。

員工登入 `/admin/orders`：shop_staff可讀摘要／詳情／audit，shop_admin可操作模擬出貨与送達。需PAID且沒有待核對通知，貨件ID由伺服器產生；不會建立物流託運。顧客 `/shop/orders` 手動核對配送進度。回應未知保留同UUID與body重試，409先重讀，不覆寫或新建另一筆。

正式JAR ready後，後端 `python3 scripts/smoke_fulfillment.py`；前端 `PLAYWRIGHT_MODULE_PATH=/絕對路徑/playwright node scripts/smoke_fulfillment_browser.cjs`，提供同樣可信私有demo.env（含展示員工密碼）與Chromium。腳本只允許loopback、只用可拋棄隔離DB／Redis；操作是真實寫入，含成功後遺失回應與重試。合成訂單／稽核保留，cleanup只取消自建pending、封存自建product，不刪共享歷史。

本輪完整package104項：102通過、2舊升級marker略過；新增14履約整合與1關閉功能測試。production dist／JAR HTTP189／browser140全部通過；重跑結果與截圖留忽略work/fulfillment-20261007，歷史smoke結果仍在各自work階段資料夾。Maven普通未啟用DB測試不代表跑過真實履約；真實退款／物流与本機原資料驗收另列待補。


## 里程碑3B：模擬退款與異常付款核對

最新demo Flyway V1–V9，V1–V8不改。新環境產生器讀.env.example含MOCK_REFUNDS_ENABLED=true；已有可信後端work/demo.env先跑 `python3 scripts/init_refund_env.py`，只补缺開關，不重設憑證／既有false。程序載入可信私有env；非demo／未設定預設false，歷史可讀、退款寫入404。

店長在/admin/orders開啟訂單，填操作說明後選全額模擬退款或待核對付款退款；普通員工只讀。訂單只PAID未出貨、無未處理review可退款並回補；review退款不動庫存。顧客/shop/orders核對本人訂單及各付款的退款金額／時間。原通知與員工audit保留，不轉帳或傳訊。

正式JAR auth/csrf回200，前端production dist／preview就緒後，後端 `python3 scripts/smoke_refunds.py`；前端帶可信相同demo.env與Playwright／Chromium跑 `node scripts/smoke_refunds_browser.cjs`。只用可拋棄loopback MySQL／Redis，寫入合成fixture與失去回應演練；成果在忽略work/refunds-20261007。未知回應重試同ID，重整查order／refunds；不要重新建立另一筆。最新測試數以HANDOFF／退款verification為準，已出貨退貨／正式退款政策仍待補。


## 里程碑3C：已出貨整筆退貨

最新demo Flyway V1–V10，V1–V9不改。已有可信後端work/demo.env跑 `python3 scripts/init_returns_env.py`，只補缺少MOCK_RETURNS_ENABLED=true，不覆蓋false／憑證；新的init_demo_env從.env.example載入。Spring仍需程序環境，未指定預設false。退貨退款同時需MOCK_REFUNDS_ENABLED。以上較早段落為當時完成度，最新以HANDOFF為準。

顧客/shop/orders已出貨訂單填原因提出一次整筆申請；店長/admin/orders填說明、明確選可重新入庫或不回補，再記錄模擬收貨驗收。驗收不動庫存；後續退款依處置回補一次或保持stock。普通員工唯讀；拒絕申請不退款／不回補、恢復無review訂單配送，此demo不重申請。退款保留原配送紀錄，不安排寄送或轉帳。

先等最終JAR auth/csrf回200，production dist／preview就緒，再跑後端 `python3 scripts/smoke_returns.py` 與前端 `PLAYWRIGHT_MODULE_PATH=/絕對路徑/playwright PLAYWRIGHT_CHROMIUM_PATH=/絕對路徑/chromium node scripts/smoke_returns_browser.cjs`。匯入可信私有demo環境；只允許loopback可拋棄DB／Redis，操作是真實合成寫入，包含ACK後abort／同操作重試。原始結果／手機截圖在忽略work/returns-20261007。只取消自己pending／封存fixture、保留sharedledger；不要在正式資料跑。未知結果保留同UUID／body，重整先核對，不建立另一筆。完整驗證及2項歷史marker略過原因見退貨verification／HANDOFF。


## 商品主圖：SeaweedFS

最新demo包含Flyway V1–V11，V1–V10不改。在後端建立可信work/demo.env後，執行 `python3 scripts/init_images_env.py`，只补缺SHOP_IMAGES_*與忽略的私有work/seaweed-s3.json，不重設既有憑證／false。程序仍需載入私有環境，Spring不自動讀檔；前端不可用VITE_*保存S3私鑰。

`docker compose -p elpsy-shop-demo -f docker-compose.demo.yml --profile images up -d`啟動専用MySQL／Redis／SeaweedFS，S3只loopback18333；私有bucket figura-images，leveldb2 metadata及物件volume持久。profile不啟用就不啟動SeaweedFS；SHOP_IMAGES_ENABLED預設false，新寫404。演練init-bucket=true可建立缺少bucket，正式預建bucket／最小憑證且init-bucket=false。固定3.85digest、12個128MB小volume為演練；NO_PROXY追加內部seaweed，保留既有HTTP代理。不要把専用資料volume或真實bucket拿來跑清理測試。

員工/admin/products先存草稿或重新載入未存表單，再在商品主圖選5MB以內PNG／JPEG、填替代文字，上傳／替換／移除；兩種員工均可改草稿，店長上架。顧客列表／詳情真實顯示主圖，沒有圖／失敗顯示fallback。主圖使用商品version，上架先下架才改；未知回應凍結並重試同UUID／檔案，重整先讀圖與audit。圖片不改SKU／stock，舊圖及失敗暫存保留至少10分鐘再排程清理；封存商品當前圖保留供員工查核。

最終JAR auth/csrf回200、前端production build與preview ready後，`python3 scripts/smoke_images.py`與`PLAYWRIGHT_MODULE_PATH=/絕對路徑/playwright PLAYWRIGHT_CHROMIUM_PATH=/絕對路徑/chromium node scripts/smoke_images_browser.cjs`。需要相同私有demo環境，真實寫入専用loopbackS3／DB；work/images-20261007保存結果／手機圖，不刪共享ledger。完整Maven實測需額外SHOP_IMAGES_INTEGRATION=true與啟用真實S3，仍需原MySQL／Redisflags。普通無S3測試不能宣稱實際物件驗證。最新版數量／2歴史marker略過見HANDOFF。舊/upload／avatar及任意本地path入口已停用，沒有MinIO新方案或員工頭像上傳。
