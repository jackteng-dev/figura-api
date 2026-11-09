# 商品主圖與 SeaweedFS

2026-10-07（Asia/Taipei）。本里程碑完成每件商品一張主圖，支援草稿上傳／替換／移除及上架後匿名顯示。使用免費自架 SeaweedFS 3.85 與通用 AWS SDK for Java v2 S3 client；沒有啟動 MinIO、建立外部付費服務或正式部署。多圖排序／SKU圖、縮圖轉檔、預簽名直傳另階段。

base `/ELPSY/api`，JSON回Result的data；圖片內容直接回binary。所有寫入仍需員工Cookie、CSRF及即時DB啟用／授權，顧客realm不得寫。公開圖片只GET且需PUBLISHED、有啟用SKU、imageId仍為目前主圖。沒有主圖可上架；上架後需先下架才改圖。普通demo員工及店長皆可管理草稿主圖，只有店長能上架。

| Method／path | 權限 | 請求／結果 |
| --- | --- | --- |
| GET `/admin/products/{id}/image` | catalog.images.read | `{enabled,productId,version,status,image,events}`，稽核最新50筆 |
| POST 同path，multipart/form-data | catalog.images.upload | requestId小寫UUID、expectedVersion非負整數、alt非空最多160字、file；→`{event,current,replayed}` |
| DELETE 同path，JSON | catalog.images.remove | `{requestId,expectedVersion}`；→同上 |
| GET `/admin/products/{id}/image/{imageId}` | catalog.images.content | 目前主圖內容，包含草稿／封存商品，需員工身分 |
| GET `/catalog/products/{id}/image/{imageId}` | 匿名 | 只目前已上架且有啟用SKU商品主圖內容 |

Admin／Public Product View都新增nullable `image:{id,url,alt,width,height}`；url是同源`/api/...`路徑。公開投影不含objectKey、bucket、憑證、員工或內部audit；後端驗證當前商品狀態再讀取私有S3物件，沒有公開bucket或對外預簽名網址，前端不需S3 CORS。替換／移除後舊URL及下架後已知公開URL下一次讀取404。binary使用實際解碼類型、no-store及nosniff；讀取失敗503，UI顯示fallback。已傳到瀏覽器的圖片無法撤回。

PNG／JPEG限定5MiB、每邊1–4096、最多1600萬像素。驗證真實解碼格式與MIME，先查尺寸再完整解碼；拒絕SVG／GIF／壞檔／超限／MIME偽裝。圖片重新編碼，去除原metadata及附加內容，PNG保留透明；不採用原filename／路徑。alt只trim並以文字／HTML alt顯示，不執行HTML。multipart全域file5MB/request6MB；超限413，其餘格式／欄位400。客戶端不可指定owner／actor／objectKey／URL／商品版本以外內容；不允許未知欄位、重複文字參數或小數版本。不存在／非目前／非公開404，Cookie／realm401，CSRF／權限403，版本／狀態／內容碰撞409，儲存暫不可用503。

## 版本、重送與跨儲存一致性

圖片變更使用商品version，不改SKU或stock；DRAFT才新寫，UPLOAD包含首次及替換，REMOVE需已有主圖。每成功操作version+1、原actor DB username／原version／imageId／action及時間入稽核。全域requestId核對actor、product、action、原version、來源bytes SHA256與trim alt；同ID原內容重送回原event與目前Detail，沒有再PUT或新增imageId，不倒退。相同ID改圖／alt／actor／商品409；過期版次不覆寫。source SHA及normalized SHA分開，檔名不參與身分。

SQL與S3不是同一交易。操作READ_COMMITTED，啟用員工／grant→product→cleanup row；先獨立REQUIRES_NEW交易保留隨機不可變objectKey的cleanup項（無FK），再鎖該項、PUT私有物件，於主SQL transaction寫asset／current cover／version／audit及移除新key的cleanup保留。替换／移除的舊key在同SQL交易排入cleanup。SQL稽核、UUID衝突等失敗時全部SQL回滾，新key保留在durable cleanup；S3晚到成功／程序中斷也有已保存的key可清理，重試產生另一个key。不能將此流程稱分散式原子提交。

清理預設每60秒最多25項、至少10分鐘寬限，FOR UPDATE SKIP LOCKED與寫入保留鎖協調，先查是否仍被current cover引用；active物件不刪。DELETE成功才移除queue，失敗attempts+1且延後5分鐘再試，S3delete冪等；資料／audit保存不刪。既有封存商品主圖保留供員工查核；主圖移除／替换及失敗暫存才GC。清理需要DB／S3正常及排程持續執行，未提供監控／人工重試UI與壓測保證。

員工畫面先儲存／重新載入未儲存的商品內容再改圖，避免混用版本；回應未知保留同UUID／File／body並凍結圖片與商品編輯，原頁同操作重試。ACK後先清pending，再讀目前商品及圖片；GET失敗只核對，不能重新建立操作。已知400／401／403／404／409／413保留未送alt／file，重讀後再選擇操作。File／pending只元件記憶體，重整後先查目前圖及audit；不提供離線佇列。

## 私有設定與隔離服務

`SHOP_IMAGES_ENABLED`預設false；false時新寫404，metadata／audit仍可讀，binary儲存不可用503。endpoint／access-key／secret-key／bucket只後端程序環境，不能進Git／VITE_*。init-bucket預設false，demo可明確true建立缺少的專用bucket，不重設既有憑證或bucket。現有可信work/demo.env執行`python3 scripts/init_images_env.py`只補缺設定，並建立忽略的work/seaweed-s3.json；新的init_demo_env生成私有key後仍需images initializer產生S3設定檔。

`docker compose -p elpsy-shop-demo -f docker-compose.demo.yml --profile images up -d`啟動專用MySQL／Redis／SeaweedFS；S3僅loopback18333、bucket elpsy-shop-images-demo、設定憑證權限只此bucket。其餘master／filer／volume端口不向host公開；NO_PROXY只追加內部service與loopback，保留代理政策。leveldb2 metadata與volume存於seaweed-demo-data；固定3.85 image digest，volume.max12、128MB小volume為演練容量設定，不表示容錯。AWS SDK2.31.68使用path style、URLConnection、明確timeout/retry，optional checksum WHEN_REQUIRED以兼容此S3服務；SQL normalized SHA256另驗內容完整性。

V11新增四表與54–57權限，V1–V10不改checksum。舊Minio helper與依賴移除；舊/upload三入口已停用410（仍先過安全守門），不再接受任意本地path。員工頭像上傳尚未接新儲存，預設頭像保持；本里程碑只商品主圖，不移轉原圖片或重寫歷史avatar網址。

實際測試、升級與限制見[交接](HANDOFF.md)及[圖片證據](verification/images-2026-10-07.json)。正式環境須自行備份／隔離還原原DB與物件／metadata、HTTPS及最小憑證、儲存配額／cleanup監控、個資與素材授權、SeaweedFS安全維護及故障演練；本地preview不等於正式部署。
