# 顧客 App API（2026-10-07）

使用獨立 Flutter 倉庫 [Shop_app](https://github.com/jackteng-dev/Shop_app)，共用本後端與既有資料庫。無新 Flyway migration，V1–V18 不改。網站 `/api/customer` Cookie／CSRF 合約保留，員工 API 不對 App 開放。

所有 URL 以 `https://<host>/ELPSY/api` 為前綴。公開商品與預購依舊 GET `/catalog/products`、`/catalog/preorders` 及詳情／主圖，不帶 Bearer。回應沿用 `{code,message,data}`。

## 登入

| POST `/mobile/customer/auth/...` | JSON | data |
| --- | --- | --- |
| register | username/password/displayName；與網站相同驗證 | Customer View，201 |
| login | username/password/deviceId（標準小寫 UUID）/rememberMe=false | accessToken/refreshToken/expiresIn/customer |
| refresh | refreshToken | 新 token pair，舊 pair 立即失效 |
| logout | refreshToken | 空，撤銷該裝置目前 pair；舊 pair 不撤銷新 pair |

access 15 分鐘、refresh 7 天；refresh 輪替用 Redis 原子 compare-and-set，並行只有一個成功。JWT `realm=customer_app`，Redis `shop:customer:mobile:session:{customer}:{device}`，與網站 customer/staff 分離；Redis 保存 session jti，不保存原 token。每次受保護請求同時核對 session 與 DB 會員啟用。

登入回傳 JSON 而非 Cookie，回應 no-store；password/refresh DTO 不序列化、不顯示於 toString。無登入自動重送；refresh 回應遺失可能需要重新登入，不使用舊 refresh 反覆重送。登出本機先清除，遠端未知須顯示撤銷未確認；Redis connect/command timeout 預設2秒（REDIS_CONNECT_TIMEOUT/REDIS_COMMAND_TIMEOUT 可配置）；Redis／DB 暫時失效受保護請求503，不能退化為信任 JWT。正式會員安全仍需限流／密碼重設等另驗。

## 業務路由

原 `/customer` 合約以 `/mobile/customer` aliases 重用相同 Controller／Service，body、版本、UUID、safe projection 與本人隔離完全相同：me GET/PATCH、cart GET/items PUT/DELETE、addresses GET/UUID PUT/DELETE、orders 與 preorders GET/POST/詳情/取消、mock-payments GET/POST、mock-payments 或 preorder-payments simulate POST、現貨 mock-return GET/POST。詳情見既有 CUSTOMER、SHOPPING、ORDERS、PAYMENTS、PREORDERS API 文件。

只此 namespace 使用獨立 SecurityFilterChain，忽略所有 Cookie，只接受 `Authorization: Bearer <customer_app access>`。原生入口不使用 CSRF；網站寫入即使帶原生 Bearer 仍須 CSRF。精確方法＋path allowlist 與網站共用，未知路由／方法拒絕；網站或員工 realm token、refresh 當 access、原生 Cookie-only 都401，無越權／轉發／共用員工入口。原 HMAC callback 路徑与唯一 exemptions 不改，mock feature flags 仍預設false。

## App 寫入

以 secure storage 保存原 owner／UUID／完整 body；未知5xx／斷線凍結新寫入，同 ID/body 才能明確重试冪等操作。cart/address/profile/現貨取消沒有 UUID，不能盲重送，先 GET 目前資料供使用者核對。ACK即移除 pending，後續讀取失敗只能讀取。refresh只可針對後端先拒絕的401單次更新，沒有一般network自動重試。

## 證據

完整 Maven 與新真 MySQL/Redis native identity tests、`scripts/smoke_mobile.py` 實際 loopback 流程；本輪 safe JSON 見 verification/customer-app-2026-10-07.json。所有合成 fixture／帳密／原始日誌在 ignored work，不能把模擬流程或 Flutter 瀏覽器 harness 稱為真機／上架完成。
