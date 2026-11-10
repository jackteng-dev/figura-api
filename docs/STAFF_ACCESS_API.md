# 員工、角色與存取治理（V12）

Context path為 `/figura`，下表均以 `/api/admin/access` 為前綴。員工Cookie／CSRF與顧客身分分開。回應沿用 `{code,message,data}`；actor只取已驗證Principal，username再次由DB查詢。新增V12，V1–V11不改；原DB需先備份、隔離升級驗證，不能自動baseline／clean。

| 方法／路徑 | 穩定權限 | 內容 |
| --- | --- | --- |
| GET /staff | access.staff.read | 安全員工列表：id／username／enabled／version／roleIds |
| POST /staff | access.staff.create | 新增員工，BCrypt初始密碼、不寄通知 |
| PUT /staff/{id} | access.staff.update | 明確啟停／角色指派，不接受username改名或密碼重設 |
| GET /roles | access.roles.read | 角色／enabled／version／permissionIds |
| POST /roles | access.roles.create | 新角色，expectedVersion=0 |
| PUT /roles/{id} | access.roles.update | 名稱／說明／啟停／固定權限指派 |
| GET /permissions | access.permissions.read | 唯讀程式migration定義的權限目錄，不回URL／method |
| GET /audit | access.audit.read | 唯讀異動前後狀態、actor快照、原因、操作ID與時間 |

列表page預設1；staff／audit每頁預設20、roles50，允許1–50、page上限100000。回應含items/page/pageSize/total；permissions目前為小型固定目錄，無任意建立／更改授權路徑API。V1舊唯讀權限2–4保留成legacy.*，移除舊選單位置；新的選單依access.*稳定code建立。

## 寫入合約

所有新寫入需小寫標準UUID `requestId`、非空原因 `note`（最多500字）。更新需原 `expectedVersion`，新角色需0。POST成功201，相同已提交操作重送200且replayed=true；UUID與actor／action／target／內容不一致409。版本過期409，缺資源404，非法／未知欄位400，URL＋method或完整管理能力不足403。舊員工註冊／stopAccount／raw User更新／角色指派等寫入已停用410，仍先通過登入及CSRF，不能藉legacy旁路管理。

POST staff欄位：username（3–64英數底線）、password（12字元以上、UTF-8最多72 bytes）、enabled、roleIds（1–50）、requestId、note。PUT staff：enabled、roleIds、expectedVersion、requestId、note。roleIds必須存在且不可重複；可保留停用角色指派，但它不授權，員工沒有任何有效權限便不能登入。

POST／PUT roles欄位：name（最多64）、description（最多255）、enabled、permissionIds（最多100）、expectedVersion、requestId、note。ID不可重複或超出固定目錄。角色ID沿用既有非AUTO_INCREMENT結構，在治理鎖下配置MAX+1；不改FK欄位型別、不停用FK檢查。API沒有刪除員工／角色或改刪稽核功能。

安全回應不含password／BCrypt／authVersion／指紋。稽核只保留安全前後投影；私有JWT key計算HMAC-SHA256內容指紋，建立員工重送會核對密碼但不把明文或可獨立字典猜測的password hash寫入ledger。JWT key換掉後，舊指紋操作不能再辨識為相同操作，需核對audit再決定，不能宣稱跨金鑰輪替可重播。

## 管理員、撤權與交易

所有治理寫入除了對應API權限，還需啟用員工持有上述8個access.*能力。可委派唯讀能力；只有部分寫入能力的角色不能用指派API升級自己。完整能力可來自多個啟用角色的聯集，沒有硬編碼role=1超級管理員。

治理鎖順序：singleton staff_access_guard → 全部員工rows依ID排序 → role／user_role／role_permission → audit。READ_COMMITTED交易內核對live actor／完整能力，修改後核對至少一位啟用且具有8能力的管理員；失敗連同版本／指派／登入版本／audit全回滾。兩個最後管理員同時自停用只允許一方成功。全體roster鎖是單店小型管理功能的保守選擇，會短暫等待／阻擋員工業務寫入；大組織或高頻治理需另做更細的鎖與負載驗證。

商品create／update／publication／archive也補齊transaction內actor→live enabled role/grant→product的順序；庫存、履約、退款、退貨、主圖沿用原live actor/grant guard，新增忽略停用角色。已先取得業務锁的操作可完成，撤權等它提交；撤權提交後再取得actor/grant鎖的寫入拒絕。不能承諾中途強行取消已獲准的交易。

啟停／角色指派真正變更會增加staff auth_version；角色enabled／permissionIds變更會增加所有指派員工的auth_version與access_version。純角色名稱／說明變更不撤銷session。access與refresh每次核對JWT authVersion=DB；停用後再啟用、移除後重加權限，旧session也不復活。Redis不跨SQL修改，舊session key自然到期。V11既有staff JWT缺authVersion，升級後員工需重新登入；顧客session不變。

## 管理頁與待確認操作

`/admin/account`員工、`/admin/roles`角色、`/admin/permissions`固定目錄、`/admin/access/audit`稽核。按鈕只是操作便利，後端才是權限判斷。編輯持有原版本，未知回應凍結body／UUID，重試原操作；ACK先解除pending，再GET核對，GET失敗只重讀。409不自動覆寫未送內容，先讀目前資料再重新開編輯。

pending只在頁面記憶體，不持久保存初始密碼；離開／重整會遺失。若自身停用／降權後ACK丟失，旧session不能重試，需以仍有權限的管理員核對audit與目前狀態。此輪沒有密碼重設／MFA／邀請／寄通知／員工頭像／多店隔離；audit對DB管理者不防竄改。正式原資料移轉與本地升級／備份還原見CLOUD_LOCAL_FOLLOWUP。
