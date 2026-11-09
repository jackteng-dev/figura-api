# ELPSY Shop 後端交接

## 2026-11-08：自動更新端到端驗證（ELPSY Shop（API））

本次只增加此交接段落；不變更應用邏輯、資料庫、帳號、映像 recipe、CI 或部署契約。

- 目的：驗證 Atlas／Encore CD 恢復與主機讀取憑證換新後，正常 PR → 原完整 CI → merge → 私人 GHCR 發布 → Ubuntu 主機自然排程部署的完整鏈仍可用。
- 無可見內容變更；以主機實際部署來源 SHA 等於本次 main 作為判定。
- 主機私人部署完成後，再確認 Funnel 公開唯讀版自動跟隨新部署版本重建。
- 未完成上述實測前，不以本段文件或映像發布宣稱自動更新 PASS；結果保存於 server-management 私人交接。

---

## 2026-10-20：Shop 首次主機 CD PASS／配對自然更新驗證

- 測試標記：SHOP-CD-VERIFY-20261020-A。本次只新增交接段落，保留完整歷史，不修改業務、schema、帳號、資料、前後端 API 或發布 recipe。
- 已完成：主機 root shop-first-1792508954444032339／SHA256 02b32c967de74fcf2368fc3e48c0b67d43ca884b4db15a4654bc4d9e8828a3fd FIRST_CD_COMPLETE/PASS；原 Web6f2bc38c／API3ba73a1a 已成對部署，actual OCI／來源與兩個固定 digest 一致。
- 資料保護：fresh 加密備份/check/NAS copy、MySQL45表完整隔離還原、Redis/Seaweed bytes、真 Web/API old→new 與故障回舊全 PASS；正式 DB/Volume 不回灌、不 clean/seed。其他四站252探測0失敗，實際版本與掛載保留，正式資料未初始化。
- 本輪實測：主機匿名瀏覽器手機/桌面8項 PASS；主機 CD 工具114項離線及回歸 PASS。原來源 Hosted CI/Private 發布已成功，沒有重新執行。這些不冒充下一個來源的自然更新結果或完整商務E2E。
- 重要範圍：首次 CD 不等於 AUTO-CD；這次 Web/API 兩repo各走正常 docs PR、原完整 CI 與 Private GHCR，合併後須等兩者新main發布都就緒，才由既有 Ubuntu timer 配對更新。尚未通過的此 head/main CI、私有新digest、自然交易與actual OCI不得先標 PASS。
- 下一步：兩個 PR 原 CI 全 PASS 後再相鄰正常合併，不 force 或跳檢查；驗 main→CI→GHCR→自然 CD→新root metadata＋完成journal/資料指紋/其他四不變。完整 Shop AUTO PASS 後接續Encore。
- 保留：MySQL/Redis/Seaweed、網站Cookie/CSRF、App /ELPSY/api 別名及原資料契約，Flutter App不在本次；沒有真金流/物流/通知、Funnel、網路/SSH/UFW/Tailscale/NAS修改或新Token。完整主機證據見 server-management，不將舊來源歷史改成目前版本。


更新：2026-10-08。顧客App完成Android模擬器原生核心驗收與可安裝測試APK，準備iOS與商家簽章；仍共用本Spring/MyBatis後端與V18資料庫，不複製業務服務。現貨／整筆預購網站MVP及原生Bearer第一版已實作，付款／配送／退款／通知仍mock，尚未正式部署或商店上架。

起始本輪main：92213e2ca8332ebfe7bbe50774b4ce5bcaa2badb；App a0418a1ed31b3614808ee87f0841003a144f3d21；Vue 1e282a8a713657e684523cec4ab09d23ed4df9c5。先fetch／讀遠端HANDOFF與AGENTS／status/log/diff／ff-only。本輪只涉及ELPSY三專案。

## 已有後端與重要檔案

/api/mobile/customer JSON Bearer realm/session 與網站Cookie/CSRF、員工realm隔離；owner只CustomerPrincipal，15分鐘access／7天refresh原子輪替、DB即時啟用核對、Redis2秒timeout，native aliases共用原cart/address/現貨／預購服務。無新增migration，V1–V18不可改。重要檔案 CustomerSessionService、MobileAuthController／MobileAuthenticationFilter、SecurityConfig、MobileIdentityIntegrationTest、scripts/smoke_mobile.py、docs/MOBILE_API.md。

第一版詳細實作與回歸見[2026-10-07歷史交接](verification/customer-app-handoff-2026-10-07.md)；V18詳細見[預購履約歷史交接](verification/preorder-fulfillment-handoff-2026-10-07.md)。

## 本輪驗證與限制

Android29 模擬器使用真 NativeStore／AndroidKeyStore 與本後端，核心4階段通過（冷啟動與輪替、已提交回覆遺失的原 UUID/body 重試同一單、ACK後GET503、版本寫入只讀核對與owner隔離）。最終產品入口 debug APK 0.1.1+2 的手動 UI／生命週期8項通過。完整 UI 自動化被 System UI ANR／UiAutomator45秒逾時阻擋；Skia 背景返回黑畫面未通過，預設renderer短暫黑畫面後恢復。實體手機、硬體儲存及效能仍未驗收。

本輪後端完整 mvn package **262項：260通過、2既有fixture skip、0failure/error**，原生真HTTP **19通過**，DB **39項invariant全0**、history18，V1–V18 bytes不變；Vue production build（vue-tsc／Vite）成功。後端及Vue本輪沒有產品程式變更。前輪 Dart live1、browser11、網站HTTP153及API故障10屬歷史證據，本輪未重跑，不能寫成裝置測試結果。

安全證據：[customer-app-device-2026-10-08.json](verification/customer-app-device-2026-10-08.json)。詳[App本輪交接](https://github.com/jackteng-dev/Shop_app/blob/main/docs/HANDOFF.md)、[USB安裝與iOS／正式簽章步驟](https://github.com/jackteng-dev/Shop_app/blob/main/docs/DEVICE_ACCEPTANCE.md)。

## 下一步與保存

Android 測試APK在私人 Shop_app 草稿版本 tag v0.1.1-device-test；只連 localhost9090，需使用者電腦自己的 demo 與 USB adb reverse，不是正式部署。正式Android商家簽章條件設定及iOS own-app Keychain entitlement已準備；完整設定以拋棄式開發key驗過，不是商家正式簽章。iOS只有靜態plist／設定檢查，Linux未做Xcode建置。

下一步實體Android預設renderer／Keystore／背景／斷網／更新驗收，以及Mac/Xcode、Apple Team、HTTPS API下的iOS／Keychain驗證。正式部署、簽章／平台帳號、真provider、會員安全及營運另列待驗；整筆預購MVP已完成，訂金尾款／延期／部分退款仍選擇性擴充。

只用全新自有 elpsy-app-device-20261008 scope；封存自己1商品／關閉1批次，停止自有API／controller／模擬器，移除3容器／2 volumes／network，核對23306／27379／28333／29092／29102關閉。未改原DB、其他作品或寄真人訊息。私人fixture／SDK／日誌／截圖不進Git。三倉庫各自main提交／推送，不force，核對實際遠端與乾淨status。

交付補充：原雲端APK上傳端點401，改以手動GitHub Actions建置成功並附到私人草稿。[下載](https://github.com/jackteng-dev/Shop_app/releases/tag/untagged-2e7d99bc1f7c451ca04d)／[建置](https://github.com/jackteng-dev/Shop_app/actions/runs/37712548885)；實際交付SHA256 `4ac11c22115f0c073b91247f8fe1e42e26aef18fa1a07b0bc447f320899f06d1`，180297725 bytes，下載雜湊／開發簽章／code2／3 ABIs已核對。原生核心及手动UI證據屬原雲端APK；CI重新建置檔尚未跑裝置驗收，簽章亦不同，不能合併宣稱同檔驗證。
