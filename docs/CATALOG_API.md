# 商品／SKU API（里程碑1B）

前綴為 `/figura/api`；所有回應沿用 `{code,message,data}`。商品 ID／SKU ID 是 UUID，價格固定新台幣**整數元**（1–99,999,999）；不使用浮點金額。上架表示可瀏覽，沒有庫存或結帳承諾。

| method | path | 權限／結果 |
| --- | --- | --- |
| GET | /catalog/products?page=1&pageSize=12 | 匿名；items/page/pageSize/total，page 1–100000，pageSize 1–50 |
| GET | /catalog/products/{id} | 匿名；只回 PUBLISHED 且有啟用 SKU，其他404 |
| GET | /admin/products | catalog.read.list；同分頁合約，含草稿／封存 |
| GET | /admin/products/{id} | catalog.read.detail |
| POST | /admin/products | catalog.write.create；建立草稿，201 |
| PUT | /admin/products/{id} | catalog.write.update；完整更新草稿／SKU |
| PATCH | /admin/products/{id}/publication | catalog.publish；上架／下架 |
| DELETE | /admin/products/{id}?expectedVersion=n | catalog.write.archive；僅草稿軟封存，200 |

寫入須已驗證員工 Cookie＋CSRF（GET `/auth/csrf`，X-XSRF-TOKEN header）；顧客 Cookie 不能代替員工。伺服器每請求從 DB 核對 URL＋method 權限；前端按鈕不是授權。展示員工有 read/write，店長另有 publish；員工與角色管理另見 STAFF_ACCESS_API.md。

建立與更新只接受以下欄位；未知欄位400，不能直接送 status／version／庫存／owner：

```json
{
  "title": "收藏公仔",
  "brand": "ELPSY",
  "category": "公仔",
  "description": "商品文字說明",
  "expectedVersion": 0,
  "skus": [
    {"code": "FIGURE-001", "label": "標準款", "priceTwd": 1280, "enabled": true}
  ]
}
```

title 最長120、brand/category 最長80、description 最長8000且皆不可空白。每件1–50款 SKU；label 最長80，code 為英數／底線／連字號1–64，正規化大寫且跨商品、停用、封存全域唯一。

建立 expectedVersion 必須0，不允許指定已有 SKU ID。更新每個已有 SKU 必須附伺服器 ID 並保留在 skus 中，code 不可改；可改 label／priceTwd／enabled。新增 SKU 不附 ID。不可跨商品帶 ID、刪除既有款式或重複 ID；不販售改 enabled=false。商品與全部 SKU 同一 MySQL transaction，任一失敗整筆回滾，重複全域編碼409。

更新／上架／下架／封存都須 expectedVersion；商品列鎖＋版本核對，成功遞增版本，過期409並不覆寫。已上架要先由 publish 權限下架才能編輯，避免 write 權限直接修改公開價格；封存保留商品與所有 SKU，無復原 API。上架至少一款啟用，封存不得上架。publication body 僅 `{ "expectedVersion": 1, "published": true }`。

公開投影只含 id/title/brand/category/description/skus/image/work；SKU 只含 id/label/priceTwd/available。隱藏版本、狀態、SKU 編碼與停用款。商品說明為純文字，前端不插入 HTML。列表按 created_at／id 倒序，同一 DB 快照讀取；跨請求分頁遇新增商品仍可能移動，尚未使用 cursor。

Flyway 新增 V3，V1/V2不改 checksum；只在 exact `elpsy_shop_demo` 的 demo profile 執行。沒有種假商品、沒有改原 DB。V13導入品牌／作品／分類主檔、商品固定ID參照與相容字串寫入，詳見MASTERS_API.md；圖片／購物／模擬交易已各有後續里程碑，預購仍待做。V4庫存調整與available合約另見INVENTORY_API.md。


## V11商品主圖

Admin／Public View新增nullable image安全欄位{id,url,alt,width,height}，目前每件商品一張。主圖獨立API變更、必須DRAFT並核對同商品version；商品DTO不能寫URL／objectKey。詳見[IMAGES_API](IMAGES_API.md)。無圖仍可上架；下架後已知公開圖片URL404，員工可讀目前草稿／封存圖。圖片不改價格／SKU／inventory，舊MinIO及本地檔案入口已停用。

## V13商品主檔

新前端送brandId/categoryId必填UUID與nullable workId，替代上述legacy字串欄位；公開及管理顯示採live主檔名稱、公開work可為null。舊字串模式只解析已有主檔，不自動建立。所有停用／改名／guard與升級規則見[MASTERS_API](MASTERS_API.md)。
