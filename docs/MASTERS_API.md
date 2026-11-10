# 品牌／作品／分類主檔（V13）

前綴 `/figura/api`，沿用 `{code,message,data}`。品牌 BRAND、作品 WORK、分類 CATEGORY 各有獨立表與不可變 UUID；名稱最長80、說明500，enabled、version。名稱 trim、同類唯一且大小寫／重音敏感；不自動合併不同拼法、不猜作品、不刪除主檔。

| method | path | 權限 |
| --- | --- | --- |
| GET | /admin/masters?kind=BRAND&page=1&pageSize=20 | masters.read |
| GET | /admin/masters/options?kind=WORK&page=1&pageSize=50 | masters.options |
| GET | /admin/masters/audit?page=1&pageSize=20 | masters.audit |
| POST | /admin/masters/{kind} | masters.create |
| PUT | /admin/masters/{kind}/{id} | masters.update |

kind 必須精確 BRAND／WORK／CATEGORY。page 1–100000、pageSize 1–50，選項包含停用項供保留舊關聯。普通員工可讀／選擇／稽核，店長可新增／修改；employee Cookie、CSRF 與每次即時 DB grant，顧客 realm 不能代替。固定 SQL table／column 白名單，不接受任意 identifier。沒有 DELETE 或自訂權限路徑。

寫入範例：

```json
{"name":"作品名稱","description":"說明","enabled":true,"expectedVersion":0,"requestId":"4b97a120-9394-4e36-890b-e36061676809","note":"異動原因"}
```

新增版本0；修改原版本，成功 version+1。未知欄位400、版本或名稱衝突409、不存在／錯類型 ID 404。成功初次201、重播200，回 `{event,current,replayed}`。全域 UUID 核對 actor／kind／action／target／trim 後內容，不同操作409；重送回原event加目前主檔。交易內 entity／版本／audit一起保存，audit故障全回滾。event包含安全前後狀態與原因、時間 Asia/Taipei，不回內部fingerprint。紀錄不提供刪改 API，DB管理者可改資料，非防竄改證據。

商品品牌／分類必須指定配對 brandId／categoryId；workId 選填，ID模式 null 明確清除作品。前端只送 ID與商品metadata／SKU白名單。相容舊客戶的 brand／category 字串僅解析**已存在且精確同名**主檔，不得透過商品 API新增；舊模式未送 workId 保留既有作品。ID與名稱同送時若不一致409。獨立三個FK拒絕錯類型ID。

停用主檔不得新增指派；既有草稿可保留並編輯其他資料，重新上架必須全部引用主檔enabled。上架商品引用的任何主檔不能停用，先下架。改名即時用於 Admin／Public商品讀取，保留商品UUID／版本、原字串及所有訂單快照；下一次明確儲存商品才更新缓存字串。公開新增nullable work名稱，不曝露主檔ID／enabled／版本／稽核／actor。

所有主檔寫入及商品 create/update/publication/archive 取得即時 actor/grant→主檔 singleton→product。主檔停用以 READ_COMMITTED 在同guard下核對已上架引用，與商品上架互斥，不能產生PUBLISHED＋停用引用。訂單仍 customer→排序product，讀取快照不新增反向master鎖。此保守guard適合單店主檔低頻異動，未宣稱大型高併發。

前端 `/admin/masters` 有三類列表、新增、改名／啟停與唯讀稽核、分頁；商品改成三個選項。未知ACK凍結同UUID／body重試，ACK後GET故障只重讀；409保留未送內容且需重讀後重新開編輯。pending只記憶體，重整／離開後請先核對audit，不另送新操作。選項以50筆分頁完整載入，尚無autocomplete／階層分類／多品牌作品關聯。

V13只用於exact隔離 `elpsy_shop_demo`，V1–V12不改。既有brand/category trim去重並補ID、保留原欄位／productversion／SKU／inventory／order；同名跨類型分離、case不同各自保留。work不猜，一律null。最小demo bootstrap ELPSY／公仔，不虛構migration的員工audit。空白legacy名稱使非null轉換失敗；正式原資料必須先列出異常、備份、隔離還原並規劃升級，不可拿demo migration直接套原DB或repair／baseline／clean。

驗證入口：後端 `CatalogMasterIntegrationTest`、`scripts/smoke_masters.py`；前端 `scripts/smoke_masters_browser.cjs`。私有env／S3config／資料／rawlog／截圖在忽略work，安全verification另列實際結果。
