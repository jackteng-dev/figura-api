# 客服、政策頁與物流追蹤 API（Commerce V2 Phase 6，V29）

## 政策／說明頁

V29 建立 8 個**空白草稿**：`faq`、`contact`（HELP）、`preorder-policy`、`shipping-policy`、`cancellation-refund`、`returns-defects`、`privacy`、`terms`（POLICY）。系統不寫入任何營運承諾（退貨天數、保固、賠償等），正式內容需作者撰寫。

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/site-pages`、`/site-pages/{slug}` | 公開；`{slug, kind, published, title, body, publishedAt, revision}`。未發布 `published=false`、`body=null`，前台顯示「條款審核中」 |
| GET | `/admin/site-pages`、`/admin/site-pages/{slug}` | 草稿、已發布版本、操作紀錄（`pages.read`／`pages.detail`） |
| PUT | `/admin/site-pages/{slug}` | `{requestId, expectedVersion, title, body, note}` 編輯草稿（`pages.edit`）；送審中再編輯會退回草稿；不影響已發布版本 |
| POST | `/admin/site-pages/{slug}/submit` | 送審（`pages.submit`）；空白內容 409 |
| POST | `/admin/site-pages/{slug}/publish` | 發布（`pages.publish`，店長）；**發布者不能是最後編輯草稿的人**（409） |
| POST | `/admin/site-pages/{slug}/reject` | 退回草稿（`pages.reject`，店長） |

權限預設：員工可編輯與送審；店長另可發布與退回。操作寫入 `site_page_event`（全域 requestId、指紋核對）。

## 客服單

類別：`ORDER`、`PREORDER`、`SHIPPING`、`RETURN_DEFECT`、`PAYMENT`、`ACCOUNT`、`PRODUCT`、`OTHER`。狀態：`WAITING_STAFF`、`WAITING_CUSTOMER`、`RESOLVED`、`CLOSED`。

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET／POST | `/customer/support-tickets` | 本人清單／建立 `{requestId, category, subject, body, orderId?, preorderOrderId?}`（關聯訂單須為本人；處理中上限 20） |
| GET | `/customer/support-tickets/{id}` | 本人詳情；**不含內部備註** |
| POST | `/customer/support-tickets/{id}/messages` | `{requestId, body}`；狀態改為 WAITING_STAFF；CLOSED 後 409 |
| GET | `/admin/support-tickets?status&assigneeId` | 佇列（待處理優先） |
| GET | `/admin/support-tickets/{id}` | 含內部備註 |
| POST | `/admin/support-tickets/{id}/messages` | `{requestId, body, internal?}`；對顧客回覆 → WAITING_CUSTOMER |
| POST | `/admin/support-tickets/{id}/status`、`/assign` | `{requestId, expectedVersion, status | assigneeId, note}`，寫入 `support_ticket_event` |

App 走 `/mobile/customer/support-tickets...`。

## 物流追蹤（延伸 V23 出貨單）

事件碼：`PICKED_UP`、`IN_TRANSIT`、`ARRIVED_STORE`（限超商）、`OUT_FOR_DELIVERY`（限宅配）、`EXCEPTION`、`RETURNED`。來源只有 `TEST`。

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/customer/shipments/{id}/tracking` | 本人出貨單時間軸 `{shipmentId, method, carrier, trackingNumber, testCarrier, events[]}` |
| GET | `/admin/shipments/{id}/tracking` | 員工（`shipments.tracking.read`） |
| POST | `/admin/shipments/{id}/tracking` | `{requestId, code, description?}`（`shipments.tracking.mock`，需 `MOCK_FULFILLMENT_ENABLED`、已出貨） |

到店與配送異常寫一則站內通知（`SHIPMENT_ARRIVED_STORE`、`SHIPMENT_EXCEPTION`）。正式物流需實作 `TrackingService.CarrierAdapter`（依貨件號查詢承運商狀態並寫入同一張表），並取得物流商帳號／API 金鑰；列在待啟用清單。
