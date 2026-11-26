# 出貨單合併與私人倉庫 API（Commerce V2 Phase 5，V28）

延伸 V23 出貨單（`SHIPPING_API` 見 HANDOFF 2026-11-18）。顧客 Web `/api/customer/...`（Cookie＋CSRF）、App `/api/mobile/customer/...`（Bearer），路徑與回應相同。

## 原則

- **不動錢、不動庫存**：合併只把訂單搬到新的出貨單（`shipment_item`），訂單金額、運費欄位（`shipping_twd`）、付款、退款、庫存與配額都不變。
- **不重複收費**：新出貨單運費＝依合併後商品金額與目前運費規則重算，且**不超過原運費總和**。原運費較多時建立一筆 `PENDING_REVIEW` 的運費調整（差額），由店長審核；`CREDITED_MOCK` 只是狀態，不會轉帳或退款。
- **暫存中不出貨**：暫存在私人倉庫的出貨單不能用出貨單端點或單筆訂單端點出貨；顧客取消暫存或員工解除後才可出貨。
- 暫存期限 `shop.warehouse.storage-days`（預設 60 天，**測試值，正式政策待作者核定**）；到期前 `reminder-days`（預設 7）天寫一則站內通知，排程 `shop.warehouse.reminder-enabled` 預設 false。逾期目前只標示 `overdue`，**不自動出貨、不收費、不取消**（正式政策待定）。

## 顧客

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/customer/shipments?page&pageSize` | `{items: [{shipment, canHold, canRelease, mergeable, blockedReason}], page, pageSize, total, storageDays}`；不含已併入其他出貨單者 |
| POST | `/customer/shipments/merge` | `{requestId, shipments: [{id, expectedVersion}] (2–10)}` → `{shipment, adjustment|null, replayed}` |
| POST | `/customer/shipments/{id}/hold` | `{requestId, expectedVersion}` → `{shipment, replayed}` |
| POST | `/customer/shipments/{id}/release` | 同上，取消暫存 |

合併條件：都是本人的、狀態 WAITING 或 READY、沒有已出貨的訂單、沒有付款待核對或退貨中、沒有「承擔運費的訂單已取消」（FEE）、同配送方式、收件資料或取貨門市完全相同；合併後訂單上限 51 張。任一張暫存中，合併後沿用最早的暫存起點與期限。

`shipment` 新增欄位：`mergedInto`、`heldAt`、`storageUntil`；`status` 可能為 `MERGED`。

冪等：同 `requestId`＋同內容回原結果（`replayed=true`）；同 `requestId` 不同內容 409。版本不符 409（重讀後再操作）。

## 員工（Web 後台）

| 方法 | 路徑 | 權限 | 說明 |
|---|---|---|---|
| GET | `/admin/warehouse?overdue=false` | `warehouse.read`（店長、員工） | 暫存中的出貨單，依期限排序，含 `customerId`、`overdue` |
| POST | `/admin/warehouse/{id}/release` | `warehouse.release`（店長） | `{requestId, expectedVersion, note}` |
| POST | `/admin/warehouse/{id}/extend` | `warehouse.extend`（店長） | `{requestId, expectedVersion, note, days (1–180)}`；重新計算提醒 |
| GET | `/admin/shipping-adjustments?status=` | `shipping.adjustments.read`（店長、員工） | 運費差額清單 |
| POST | `/admin/shipping-adjustments/{id}/decide` | `shipping.adjustments.decide`（店長） | `{requestId, expectedVersion, decision: CREDIT_MOCK|DISMISS, note}` |

員工操作寫入 `shipment_ops_event`（全域 requestId、不可刪改）。鎖序：actor/grant → customer → 出貨單，與出貨單出貨一致。

## 接真實金流後（待啟用）

運費差額目前只有審核狀態。接上真實金流時，`CREDIT_MOCK` 應改為呼叫部分退款（或折抵下一筆運費），並以退款 ledger 冪等核對；在那之前不得宣稱已退回差額。
