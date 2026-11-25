# 收藏、商品提醒與站內通知 API（Commerce V2 Phase 3，V27）

顧客本人資料；owner 只取已驗證的 CustomerPrincipal。Web 走 `/api/customer/...`（Cookie＋CSRF），App 走 `/api/mobile/customer/...`（Bearer），路徑與回應相同。回應包在 `{code:0,data}`。

## 收藏

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/customer/favorites?page=1&pageSize=20` | `Page<Favorite>`：`{product: PublicView, favoritedAt, alerts: string[]}`，新到舊；只列目前上架的商品（下架者隱藏，不刪除收藏） |
| GET | `/customer/favorites/ids` | `{productIds: string[]}`（最多 500，用來顯示愛心狀態） |
| PUT | `/customer/favorites/{productId}` | 冪等加入；商品須上架（否則 404）；上限 500（409）；回 `{productIds}` |
| DELETE | `/customer/favorites/{productId}` | 冪等移除；回 `{productIds}` |
| POST | `/customer/favorites/merge` | body `{productIds: uuid[] (≤100)}`：訪客暫存的收藏在登入後合併；已存在＝成功、不存在／下架／超過上限放進 `skipped`；回 `{merged, skipped, total}` |

`PublicView` 與 `GET /catalog/products/{id}` 相同（含 availability、preorder、minPriceTwd、specs）。

## 商品提醒

種類：`RESTOCK`（售完→有現貨）、`PREORDER_OPEN`（開放可下單的預購批次）、`PREORDER_CLOSING`（可下單批次距截止 48 小時內）、`RELEASE`（到了商品的預計發售日／月）。

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/customer/product-alerts` | `[{productId, title, kinds[], availability|null}]`（最多 200 件商品） |
| PUT | `/customer/product-alerts/{productId}` | body `{kinds: string[]}`，以整組取代；`[]`＝全部取消。新增提醒時商品須上架（404）；回目前 kinds（已排序） |

產生規則（`ProductAlertScanner`）：只掃有人訂閱的商品，與上次觀察比較，**只在狀態轉換時**通知；第一次觀察只建立基準，不補發歷史；同一轉換對同一顧客只會一筆（`(event_kind, source_id)` 唯一）。排程 `shop.product-alerts.scanner-enabled`（預設 false）、`fixed-delay-ms`（預設 300000）、`batch`（每 tick 商品數，預設 100）。

## 通知偏好

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/customer/notification-preferences` | `{emailEnabled, orderUpdates, productAlerts, emailVerified, emailDeliveryActive, version}`；沒有設定時為預設（Email 關、兩類開、version 0） |
| PUT | `/customer/notification-preferences` | body `{emailEnabled, orderUpdates, productAlerts, expectedVersion}`；版本不符 409（請重讀） |

偏好只控制 **Email 管道**；站內通知匣一律保留。Email 實際寄出條件：顧客啟用、Email 已驗證、`emailEnabled`、該類別開啟，且有寄送管道（`emailDeliveryActive`）。目前只有 mock（`MOCK_EMAIL_ENABLED=true` 時寫入 `customer_mock_email`，purpose=`NOTIFY`，不寄出）；正式寄信需寄件網域與金鑰，列待啟用。

## 站內通知匣

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/customer/notifications?page=1&pageSize=20&unread=false` | `{items: Notice[], page, pageSize, total, unread}` |
| POST | `/customer/notifications/read` | body `{ids: uuid[] (≤100)}` 或 `{all: true}`；只影響本人；回 `{updated, unread}` |

`Notice = {id, kind, category: ORDER|PRODUCT, title, message, orderId, preorderOrderId, productId, createdAt, readAt}`。標題／內文由伺服器依事件產生，只用安全欄位（無收件資料、員工備註）。訂單事件沿用既有 V14 outbox（下單、付款、出貨、送達、退款、尾款等）；商品提醒事件 kind 為 `PRODUCT_RESTOCK`、`PRODUCT_PREORDER_OPEN`、`PRODUCT_PREORDER_CLOSING`、`PRODUCT_RELEASE`。

## Schema（V27，只新增）

`customer_favorite`、`customer_product_alert`、`customer_notification_preference`、`product_alert_state`；`notification_outbox` 新增 `product_id`（FK）與 `read_at`，parent 檢查改為 order／preorder_order／product 恰好一個；新增 `(recipient_ref, created_at, id)`、`(product_id, created_at, id)` 索引。既有通知列與 payload 不變。
