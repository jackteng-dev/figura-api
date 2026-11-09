# ELPSY Shop 交接

更新：2026-10-07。新增独立 Flutter 顧客 App [Shop_app](https://github.com/jackteng-dev/Shop_app) 與原生 Bearer 入口，現貨／整筆預購 MVP 保留 V18。網站 Vue 仍在 Shop_back；三個倉庫共用本後端與資料庫，不複製業務服務。原預購四項 MVP 已完成，訂金尾款／延期／部分退款仍屬選擇性擴充。付款、配送、退款與通知為 mock，未正式部署或商店上架。

起始後端 4edf7d2c76f86e4b7c00ec56e8cdf3f220ca0235、Vue 79635fa8cecfe9c6f2ffd9dd4249a2b762fcd435；先 fetch／讀遠端交接與規則／檢查 status/log/diff／ff-only。本輪只涉及 ELPSY 三個專案。

## 本輪完成

- `/api/mobile/customer/auth` JSON 登入／註冊／refresh／裝置登出，獨立 customer_app realm、Redis mobile session namespace；15分鐘 access／7天 refresh 原子輪替，每次核對 DB 啟用，no-store，不回 Cookie。Redis connect/command timeout 預設2秒，避免超過App的20秒request deadline。
- 原生專用 SecurityFilterChain 只讀 Bearer、不读 Cookie；原網站 Cookie／CSRF 與 staff realm 不改。精確 customer 方法／路徑 allowlist 共用；me/cart/address/現貨和預購订单/模擬付款/退貨 aliases 呼叫原服務，owner 全由 Principal。
- 无新增 migration；V1–V18 byte 不改。App 採 Flutter/Riverpod/Dio/GoRouter，原生 secure storage；現貨／預購商品、會員、購物車、地址、結帳、付款與進度第一版見 App 交接。未完成操作安全保存原 UUID/body，版本寫入先重讀核對，沒有一般自動 network 重送。

## 驗證

- 新空專用 elpsy-shop-app-20261007 scope（MySQL8.4／Redis7／私有 SeaweedFS3.85）。完整 `mvn package` **262項：260通過、2既有歷史fixture略過、0failure/error**；新 native identity 真 MySQL/Redis 6項，含 realm/cookie隔離、所有權／mass assignment、並行 refresh 一個 winner、舊 token/replay/裝置登出、disabled account、方法／錯誤 token。
- 原生真 HTTP **19項通過**：公開商品／批次、本人地址／cart與版本、現貨下單replay／付款／送達、獨立預購下單／付款／到貨分配／送達／取消replay、safe progress、現貨庫存不被預購扣減、CSRF／員工入口隔離／輪替登出。
- 網站原 HTTP auth/customer/shopping/orders/preorder_orders/preorder_money 六組 **153項通過**；原業務完整 Maven 回歸通過。App unit/widget、實際 Dart→API、手機尺寸 Flutter browser harness 與 APK詳 App交接；browser harness 為記憶體 token 的測試入口，不是原生裝置驗證。
- 詳安全證據見 verification/customer-app-2026-10-07.json；私人fixture/logs與baseline V18 JAR留 ignored work/shop-app-20261007。

## 重要檔案

CustomerSessionService、MobileAuthController／MobileAuthPayloads／MobileAuthenticationFilter、SecurityConfig、共用 customerRouteAllowed、customer業務controller aliases、MobileIdentityIntegrationTest、scripts/smoke_mobile.py、docs/MOBILE_API.md。前輪 V18 詳情保留 [歷史交接](verification/preorder-fulfillment-handoff-2026-10-07.md)。

## 下一步及限制

App 第一版流程已開發；下一步是 Android 真機安裝／Keystore／背景恢復／斷網、macOS Xcode iOS build／Keychain／簽名及平台驗收。正式域名 HTTPS、正式簽章、商店帳號與上架、真 provider、會員安全、商家素材及原DB備份／隔離restore／持久化監控仍待環境／商家驗收，不能加入為已完成整筆預購開發 MVP 的新必要條件。

## 保存

GitHub 建倉庫 initial createRepository 權限不足；使用者已提供空私人 Shop_app，核對空遠端後接入。各階段交接／證據後 commit/push main、不force，最終 Git 與本輪服務清理見安全證據。未操作其他作品、原資料或真人訊息。

收尾驗證補充：最終 API 程序重啟／mock 關閉／Redis故障與重啟 **10項通過**，Redis驗證等待設為2秒逾時，故障回覆503；Redis清空後舊pair拒絕，重新登入才恢復。資料庫 **37項業務invariant皆0**，history18，V1–V18 byte不改；保留合法PAID／待到貨的合成訂單，不把待到貨清零當業務條件。最終Maven262／Flutter13（另live1）／UI11／native HTTP19／website HTTP153／Vue production build通過，詳safe JSON。
