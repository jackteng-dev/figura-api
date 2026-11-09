# 訂單通知 outbox（V14）

此階段是免費本地 **MOCK**：沒有 SMTP、Email、簡訊、LINE 或 App 推播，mock 接受不是真人送達。業務交易保存事件；獨立派送失敗不撤銷已提交的訂單。

前綴 `/ELPSY/api`，沿用 `{code,message,data}`。員工 Cookie／即時 DB 權限／CSRF；顧客 realm 無權查詢。普通員工唯讀，店長操作。沒有删除／修改歷史／收件人編輯 API。

| method | path | 權限 |
| --- | --- | --- |
| GET | /admin/notifications?page=1&pageSize=20&status=RETRY&orderId=UUID | notifications.read |
| GET | /admin/notifications/{id} | notifications.detail |
| POST | /admin/notifications/{id}/mock-dispatch | notifications.dispatch.mock |
| POST | /admin/notifications/{id}/retry | notifications.retry |

page 1–100000、pageSize 1–50；status 精確 PENDING／RETRY／SENT／DEAD，orderId 精確 UUID；省略表示全部。列表回安全 Message；詳情 `{message,snapshot,events,mockEnabled}`。snapshot 是不可變 JSON 字串；只有 orderId、eventKind、orderVersion、orderStatus、fulfillmentStatus、totalTwd 六欄，無姓名／電話／地址／Email／員工說明。內部 recipient_ref 是顧客 UUID、不回傳；provider fingerprint 不回傳。audit 原因只作純文字顯示。

## 交易捕捉

NotificationCapture 使用 MANDATORY 加入原交易，不作網路 I/O。order／付款／配送／退款／退貨與對應庫存及業務 ledger 完成後插入；插入失敗全部回滾。唯一 `(event_kind,source_id)`，業務原 requestId／callback eventId 重播不再插入。V14 不掃描／補發歷史訂單。

| eventKind | source_id |
| --- | --- |
| ORDER_PLACED／ORDER_CANCELLED／ORDER_EXPIRED | order UUID |
| PAYMENT_SUCCEEDED／PAYMENT_FAILED／PAYMENT_REVIEW_REQUIRED | payment UUID |
| ORDER_SHIPPED／ORDER_DELIVERED／ORDER_REFUNDED | order UUID |
| REVIEW_REFUNDED | 待核對 payment UUID |
| RETURN_REQUESTED／RETURN_RECEIVED／RETURN_REJECTED | order UUID |

只有新狀態轉換產生事件。晚到成功若需人工核對會記 PAYMENT_REVIEW_REQUIRED，快照保留原訂單狀態，不誤稱已付款。退款保留原付款與配送歷史；退貨退款共用 ORDER_REFUNDED。逾時可為已停用但仍存在的顧客保存事件。

## Mock 派送與重試

```json
{"requestId":"11e9ef82-9e9a-4cde-938e-f96d21353e43","expectedVersion":0,"note":"核對 mock 派送","mode":"SUCCESS"}
```

mode 精確 SUCCESS／FAIL_BEFORE／FAIL_AFTER_ACCEPT；retry body 不含 mode。未知欄位400。note 非空、trim、最多500；requestId UUID、原版本非負。初次與重播均200，回 `{event,current,replayed}`。全域 requestId 核對 actor／notification／action／mode／trim note／原版本；原操作重播回原 event＋目前 queue，衝突409。

- 捕捉 PENDING、version0、attempts0、cycleAttempts0、立即到期；只有已到期 PENDING／RETRY 可派送。
- 每次嘗試 attempts、cycleAttempts、version +1；接受成功→SENT，sentAt 有值；接受前失敗→RETRY、MOCK_UNAVAILABLE。
- FAIL_AFTER_ACCEPT 先獨立提交 receipt，再把主交易寫成 RETRY／MOCK_RESULT_UNKNOWN。這不是未接受，也不可換 delivery key。
- 失敗退避5／10／20／40秒；第五次→DEAD、nextAttemptAt=null。SENT 不可再送或重排。
- 人工 retry 僅 RETRY／DEAD，PENDING、立即到期、cycleAttempts歸0、version+1；保留累計 attempts、provider receipt 與全部 audit。
- delivery key 固定 queue UUID，immutable snapshot SHA 核對；provider 已接受後再試回 ACCEPTED_REPLAY，只一筆 receipt。mock receipt 是獨立提交的本地接受證據、不是寄送紀錄。

`MOCK_NOTIFICATIONS_ENABLED=false` 預設關閉 mock 派送，capture 仍保存事件。`NOTIFICATIONS_WORKER_ENABLED=false` 預設無自動派送；兩者 true 才工作。`NOTIFICATIONS_DELAY_MS` 預設30000ms，初次延遲相同。每 tick 最多20筆，各自短 transaction、FOR UPDATE SKIP LOCKED 挑最早到期，SYSTEM 唯讀 audit；失敗保留等待下一 tick。重啟依 DB 佇列恢復，不把記憶體當進度。

## 鎖與未知結果

操作 actor/grant→queue→全域命令 reservation。命令 UUID 在 provider 接受前先 INSERT，但同主交易最後才提交；跨訊息競爭同 UUID 的失敗方不會先接受。queue／版本／操作 audit 同 transaction；audit 故障全回滾。獨立 mock receipt 故意不設 queue FK，避免 REQUIRES_NEW 等待外層 queue X-lock。provider accept 後主交易失敗時 receipt 保留，原 UUID/body 重試核對既有接受。程序中斷 SQL row lock 自動釋放，沒有 PROCESSING lease 卡住。

這是本地 mock 設計，沒有宣稱跨 SQL／真外部服務的原子性或 exactly-once。真 adapter 另需有限 transport timeout、claim／lease／fencing、provider idempotency 與未知結果 reconcile；SMTP 沒有通用去重保證，不可直接把 socket send 放进現有鎖交易。

前端 `/admin/notifications`：筛選、分頁、快照、mock 情境、重排、唯讀 audit；未知 ACK 凍結原 UUID/body 重試，ACK 後 GET 故障只重讀；409 保留原因，重讀後仍需重新開詳情，不自動覆寫。pending 僅記憶體，重整先核對 queue/audit。列表分頁不是游標快照；讀與下一次派送之間可能變動，以原版本保護。

V1–V13不改；demo V14 只能用隔離 `elpsy_shop_demo`。正式原資料／backup／restore／真收件端和保存期限待本地。DB管理員仍能修改audit，不是防竄改證據；此阶段不含顧客通知中心。
