# SKU 庫存與調整稽核（里程碑1C）

API前綴 `/ELPSY/api/admin/products/{productId}/skus/{skuId}/inventory`。SKU必須屬於路徑商品，跨商品404。使用已驗證員工Cookie與既有CSRF；未知body欄位400，操作者只取認證Principal。

| method | suffix | permission code／回應 |
| --- | --- | --- |
| GET | 空 | inventory.read；skuId/quantity/version |
| GET | /adjustments?page=1&pageSize=10 | inventory.audit；items/page/pageSize/total，pageSize 1–50 |
| POST | /adjustments | inventory.adjust；首次201、相同操作確認200 |

展示員工可讀庫存／稽核，店長另可調整；產品草稿編輯權限不包含庫存調整。沒有紀錄修改／刪除API，路由與HTTP method在每請求從DB授權；原角色管理畫面仍占位。

```json
{
  "requestId": "e97f449d-00bc-4191-9237-9685d51e3fde",
  "expectedVersion": 0,
  "delta": 5,
  "reason": "RESTOCK",
  "note": "盤點後補入五件標準款"
}
```

requestId必須canonical小寫UUID。delta為非零整數，-1,000,000到1,000,000；RESTOCK只正數、DAMAGE只負數、CORRECTION可正可負。note必填且最多500字。調整後quantity在0–1,000,000，小數不截斷。庫存版本獨立於商品版本，每次成功調整增加1，不更改商品版號。

先鎖商品，再鎖SKU庫存，與商品編輯／封存鎖序一致。核對expectedVersion並同一MySQL transaction更新數量、增加版本、追加異動；稽核寫入失敗整筆回滾。DB CHECK保護數量、異動前後加總、版本連續與原因方向。不同操作同版本競爭一成功、一409；沒有自動重試過期版本或自動覆寫。

requestId為全域唯一。相同已驗證actor、SKU、expectedVersion、delta、reason、trim後note重送，回200與replayed=true，不再次更新數量；不同內容／actor／SKU重用409。首次回201、replayed=false。兩個相同操作同時發送也只生效一次。回應data為 `{adjustment,replayed}`；adjustment包含requestId／skuId、actorId／actorUsername快照、delta／reason／note、before/afterQuantity、before/afterVersion、createdAt。

歷史重送回**當次操作快照**，可能早於目前庫存；前端成功後重新GET當前數量，不能把歷史afterQuantity當成現值。封存後禁止新調整，仍可讀紀錄並確認既有已提交操作；停用SKU可調整實際存量，但不出現在公開款式。

前端網路結果未確認時保留同一requestId與原body，顯示「重試此調整」，不允許在等待期間改內容。4xx明確拒絕後可重新核對／送新操作；回應確認後清操作並重讀。此保留僅目前頁面的記憶體，切換商品、離開／重整會丟失未確認操作，需查異動後再決定；沒有跨頁／離線佇列。

公開商品SKU只新增 `available`（啟用SKU且quantity>0），不回exact數量、庫存版本或稽核操作者。零庫存商品仍可公開瀏覽，款式顯示暫無庫存。這是當次讀取的存量訊號，沒有庫存保留、購物車或結帳保證。

V4保留V1/V2/V3 checksum，既有SKU回填零庫存，新SKU建立在商品transaction一起建立零庫存列。ledger外鍵阻止有歷史的SKU硬刪；應用層不提供修改／刪除紀錄，但DB管理者仍可直接改，正式環境的最小DB授權、稽核匯出／保存策略留後續，不宣稱不可竄改帳本。測試只軟封存自建有歷史商品，留到拋棄專用DB，不能拿去清共用資料。

尚未包含訂單庫存保留／釋放、售出扣庫、倉庫／批號、多倉、預購、庫存調整簽核、通知、真實盤點資料與正式營運壓測。


## V9退款共用鎖順序

庫存調整先鎖啟用員工與inventory.adjust授權，再product／inventory，actor username取DB，避免audit actor外鍵鎖與退款反向等待。不存在／停用actor在寫入前401，撤權403；有效actor的ledger故障仍回滾qty／version與audit。其他requestId／版次／數量規則不變。
