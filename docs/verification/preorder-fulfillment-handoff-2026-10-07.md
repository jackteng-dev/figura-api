# ELPSY Shop 交接

更新：2026-10-07。完成**預購MVP第四里程碑：獨立到貨登記、整筆分配、出貨前核對與模擬出貨／送達**，兩端配對V18。原先四項整筆預購MVP開發範圍已完成，下一條主線為顧客App；訂金尾款、延期、部分退款及到貨後退貨為選擇性擴充，不加作本版MVP必要條件。付款、退款、配送及通知仍mock，未正式部署或實際收款／寄送。

起始main：後端e1653c9168426de0e9fd287817b2757df7e17b55、前端54ad16ebc00a872f63d400fbea3d598db845a53c。本輪先fetch、讀兩端遠端交接／AGENTS，核對status／branch／log／diff含staged，乾淨後ff-only。只涉及ELPSY兩A倉庫，未改其他作品。現貨交易、真私有SeaweedFS圖片、員工治理、主檔、通知及既有預購批次／下單／mock付款退款保留。

## 本輪完成

- 新獨立PreorderFulfillmentService／Controller／Payloads及V18。預購供應received／allocated／shipped與sku_inventory分開：0≤shipped≤allocated≤received≤capacity；待分配received−allocated、尚在庫received−shipped（含已分配待出貨）。付款、到貨、分配及預購出貨都不提前或重複扣現貨，也不加入現貨可售量。
- 店長可分批登記實際到貨數量，1–100000且累計≤capacity，DRAFT拒絕，OPEN／CLOSED及過截止可收貨；資料登記需人工核對實物，網站不代表實體已驗收。版本／不可變RECEIVE audit同交易，不假造customer notification。
- 僅PAID、無未處理review、NOT_ARRIVED／UNFULFILLED、整筆到貨足夠才ALLOCATE。不接受staff自訂分配數量，allocatedQuantity=order.quantity、allocatedAt／ARRIVED、order與batch version／供應audit／PREORDER_ALLOCATED safe outbox同交易。異訂單搶最後到貨只一方成功；不同batch同SKU不可借量。分配與既有未到貨退款競爭由相同鎖與version保護，最多一方成功；分配後原退款guard拒絕，不自動釋放分配或重開終態。
- SHIP／DELIVER沿用shop.fulfillment.mock-enabled（MOCK_FULFILLMENT_ENABLED）、預設false。SHIP需PAID、無review、整筆已分配且UNFULFILLED；batch shipped累計增加、onHand減quantity一次、server mock_pre_貨件／shippedAt及SHIPPED／version／audit／safe outbox同交易。DELIVER需PAID／SHIPPED／無review，只推進DELIVERED／deliveredAt與versions，不再扣量；保留原tracking／paidAt／paid quota，不釋放已售配額。晚到成功在分配後阻擋出貨、出貨後阻擋送達，核對原異常付款才解鎖。
- 即時啟用actor／live grant→customer→batch→order，與預購money/refund一致；RECEIVE只actor/grant→batch，不反向等customer。四類fulfillment共用新全域UUID空間，核對actor/action/target/原order/batch version/trim note與receipt quantity；原UUID重播原event及目前狀態、不倒退配送，同ID改內容／異actor／target409，每order ALLOCATE／SHIP／DELIVER各最多一次。跨batch/actor UUID碰撞、供應／audit／outbox故障全部rollback。
- 新staff /api/admin/preorder-arrivals清單／詳情／receive，/api/admin/preorder-orders/{id}/allocate、mock-ship、mock-deliver；250–255固定授權，店長可操作、普通staff唯讀、顧客realm不授權、寫入需Cookie／CSRF且未知欄位／浮點整數拒絕。原order detail新增fulfillment供應／canAllocate／canShip／canDeliver及audit，清單仍無shipping／owner。顧客order.fulfillment只本人安全allocation/tracking/times，無actor或內部note，別人404。
- 新staff /admin/preorder-arrivals真到貨表單／供應查詢與audit；原 /admin/preorder-orders整筆分配／模擬履約與退款共用pending鎖，/shop/preorder-orders本人到貨／貨件／送達進度。unknown ACK原UUID/body凍結全部其他操作，同命令retry；ACK後GET503先清pending只讀，409保留quantity/note，重讀再明確重開。pending頁面記憶體，重整先核對audit；普通staff無寫入表單／按鈕。到貨／履約note純文字、手機表格內部橫向scroll、document不溢出。
- PREORDER_ALLOCATED／SHIPPED／DELIVERED六欄safe outbox同業務transaction，沒有PII或staff note；新動態入口與通知label、工作首頁／API／ROADMAP更新。ROADMAP重整為目前完成／App主線／選擇性擴充／正式營運驗收，避免舊里程碑下一步看似一直增加必做。

## 驗證與build

- 新空專用elpsy_shop_demo、MySQL8.4／Redis7／真私有SeaweedFS3.85。完整mvn package **256項：254通過、2既有歷史fixture略過、0failure/error**；正式JAR成功。新增18項到貨整合，另既有21money保留：完整流程與snapshot／physical分離、容量/各guard、UUID/replay、晚到review在分配/出貨後守門、最後到貨競爭、分配vs退款競爭、receive/allocation/shipping/delivery ledger或outbox故障rollback、live grant/default flag/CSRF/realm/body、disabled owner/closed batch/archived product、安全projection/outbox與DB constraints、跨actor/batch全域UUID碰撞。兩個歷史upgrade marker未提供，不宣稱重做。
- 正式JAR真socket HTTP **464項／18套全通過**（既有436＋新28）。現貨閉環、圖片、員工存取、主檔、通知、預購批次／下單／money及新到貨履約均通過。
- vue-tsc＋Vite production build成功；正式dist→正式JAR Chromium **343項／18套全通過**（既有324＋新19）。到貨／分配／出貨丟失ACK同UUID/body；未知ACK凍結、送達ACK後GET503僅讀、退款競爭409保留原因且明確重開、普通staff唯讀、HTMLnote不執行、本人safe進度、390px兩端document不溢出、未捕捉JS錯誤0。顧客送達与staff履約合成mobile截圖人工查看，無商家素材；既有simplebar/chunk warning非阻擋。
- 起始V17正式JAR SHA對前輪證據，在新隔離demo真HTTP建立現貨mock paid/order通知及預購mock paid／payment evidence／paid quota，再套V18；**V17→V18升級13項通過**：原商品／現貨order／physical inventory/notifications、batch terms/audit/公開配額、預購shipping/price/paidAt/version snapshot、payment/refund/review及通知保留，V18只增加safe progress defaults，無憑空供應或fulfillment event。history18及V1–V17逐byte不改。只清理自己的seedfixture，不是原DB／备份restore驗收。
- **真JAR程序重啟9項通過**：API建立已付款／到貨／分配／SHIPPED訂單，停止專用JAR觀察API無listener且SQL仍PAID/SHIPPED／allocated/tracking；重啟同正式JAR核對safe snapshot、供應與audit原樣，原ship命令与payment callback重播不再扣量，退款guard仍拒絕，再送達／重播保留原貨件與paid quota，原outbox各一次、現貨不變。
- 最終**40項DB invariants皆0**。供應對receipt/allocation/shipment delta ledger及order、quota held/paid對order與quota ledger、fulfillment event quantity/batch/version/tracking及outbox/action唯一、payment/refund/safe parent／payload／role2 write grant／physical reserve一致。清理後pending0、未到貨paid0、未處理review0、已分配未送達0；已送達PAID與paid quota依法保留，未偽稱paid全部清零。184個合成batch、179個order、438個quota event、393個batch event、219payment／231payment event／107refund／183fulfillment event，history18。只透過refund/cancel/ship/deliver/close/archive清理合成fixture，不刪共用audit；詳細safe證據與source/artifact SHA見[本輪驗證](verification/preorder-fulfillment-2026-10-07.json)，舊證據不改。
- 初次失敗已解決：V18新CREATE TABLE少closing parenthesis，修正後只重建本輪可拋棄scope並重種V17；沒有更改V1–V17或用repair。舊feature-flag test service constructor增加dependency selector；新rollback fixture使用舊batch version，改操作前重讀；Mockito varargs matcher未注入故障，改any(Object[].class)，確認故障真500且供應／order／timestamp／audit全部rollback，再完整256通過。未隱藏初測結果。

## 重要檔案

後端：V18 migration、PreorderFulfillmentService／Controller／Payloads、PreorderOrderService／Payloads safe progress、PreorderMoneyService／Payloads staff detail、NotificationCapture、PreorderFulfillmentIntegrationTest、scripts/smoke_preorder_fulfillment.py；舊money guard fixture配合V18約束、history assertions18。
前端：api/preorderFulfillment.ts、PreorderArrivalsAdmin.vue、PreorderOrdersAdmin.vue共用pending鎖、CustomerPreorders.vue safe progress、api/preorderMoney/orders、dynamic router、notification labels、Main及scripts/smoke_preorder_fulfillment_browser.cjs。兩端API、ROADMAP、README、AGENTS、CLOUD_LOCAL_FOLLOWUP與verification更新。

## 下一步及限制

原四項整筆預購MVP範圍完成。下一條主線**顧客App**：盤點customer API、登入／裝置／平台與交付方式後實作，尚無App或真機完成。訂金尾款、延期、部分退款、到貨後退貨／釋放分配／轉現貨是選擇性商家擴充；不能默默加為本版MVP完成條件。這版只能正向到貨、整筆分配與mock配送，不能任意撤回登記或分配，不套現貨returns API。

正式營運另需真金流／物流／外部通知、會員安全、實際商家素材／短缺到貨與取消退款政策、HTTPS部署、原DB preflight／備份／隔離restore、監控／負載與持久volume驗收；本版mock不實際收款／退款／寄送，不是正式部署。DB管理者可改audit，非防竄改。

## 保存與清理

只涉及ELPSY兩A倉庫，免費服務，network policy enforced unrestricted／observed spec34及繼承proxy保持。private env、baseline JAR、合成資料、原始logs、screenshots與依賴留ignored work；changed files私密掃描通過。收尾停止本輪專用JAR與preview、移除elpsy-preorder-fulfillment-20261007三容器／兩data volumes，23306／27379／28333／29092／25176無listener，以verification.cleanup核對。提交前再fetch，两main各commit/push、不force；最終status乾淨與HEAD等於tracking及實際遠端由Git收尾確認。
