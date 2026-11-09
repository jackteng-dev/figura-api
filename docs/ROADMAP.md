# ELPSY Shop 開發進度

產品主線：公仔／收藏品單店現貨網站 → 整筆付款預購MVP → 顧客App。此頁只列目前狀態；歷史里程碑證據留docs/verification，各輪詳情見[HANDOFF](HANDOFF.md)。

## 已完成的網站功能

- 顧客註冊登入、商品／SKU公開查詢、購物車與本人地址。
- 現貨訂單快照、庫存保留／取消／逾時、mock付款、員工模擬出貨／送達、退款、整筆已出貨退貨與異常付款核對。
- 真私有SeaweedFS商品主圖、員工／角色／固定權限管理與audit、品牌／作品／分類主檔。
- 同業務交易安全通知outbox、本地MOCK派送／重試／DEAD重排／audit，沒有真人訊息。
- 預購MVP四項：批次／截止／供應配額；獨立下單與配額保留／釋放；整筆mock付款、取消、未到貨退款及晚到成功核對／通知；到貨登記、整筆分配、出貨前核對與模擬出貨／送達。配對V18，預購供應獨立，不提前扣現貨。

## 下一條主線

獨立 [Shop_app](https://github.com/jackteng-dev/Shop_app) Flutter 第一版已實作：會員、現貨／預購商品、購物車／地址、結帳與模擬付款、本人訂單／到貨配送進度；共用原生 Bearer 後端，schema V18。2026-10-08完成Android模擬器原生核心4階段與產品APK手動畫面／生命週期8項，提供私人草稿debug APK 0.1.1+2（USB連使用者電腦自己的demo）；完整UI自動化逾時與軟體渲染問題已記錄。商家簽章設定及iOS Keychain entitlement已準備。下一步是實體Android與Mac/iOS建置／Keychain驗收、正式憑證與HTTPS環境；尚未真機、iOS build或上架。詳[App裝置交接](https://github.com/jackteng-dev/Shop_app/blob/main/docs/DEVICE_ACCEPTANCE.md)。

## 選擇性後續擴充

訂金尾款、延期、部分退款、到貨後預購退貨、釋放分配／轉現貨、多圖及其他商家需求；不列為目前整筆預購MVP必做項。

## 正式營運另需驗收

付款／物流／退款／通知仍是mock。真provider與商家政策／素材、會員安全、HTTPS部署、原資料preflight與備份／隔離restore、持久化／監控及負載驗收仍未完成；不能把網站開發MVP當成已上線營業。[後續驗收](CLOUD_LOCAL_FOLLOWUP.md)。
