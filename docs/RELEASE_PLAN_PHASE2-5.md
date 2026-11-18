# 購物體驗 Phase 2–5 合併與上線計畫（2026-11-19）

所有 PR 都是疊加分支（每個 PR 的 base 是前一個分支），**依編號由小到大合併**；每一步 `verify` CI 通過才合併。API 的 V21／V22／V23 是受保護路徑（`src/main/resources/`），**每一個含 migration 的 PR 合併前都要先在伺服器做受控切換（contract switch）**，CD 才會接受。

## 依序合併

| 順序 | API（figura-api） | Web（figura-web） | App（figura-app） | Schema |
|---|---|---|---|---|
| 1 | #10 contact-only-login | #13 contact-only-login | #6 contact-only-login | 無 |
| 2 | #11 buy-now | #14 buy-now、#15 cart-stepper、#16 checkout-page | — | 無 |
| 3 | #12 order-center（Phase 2） | #17 order-center | #7 order-center | 無 |
| 4 | #13 unified-cart（Phase 3） | #18 unified-cart | #8 unified-cart | **V21** |
| 5 | #14 preorder-deposit（Phase 4） | #19 preorder-deposit | #9 preorder-deposit | **V22** |
| 6 | #15 shipping（Phase 5） | #20 shipping | #10 shipping | **V23** |

每一列：先合併 API（含 migration 的先做受控切換），API 部署完成且 smoke 通過後，再合併同一列的 Web；App 最後發版。新 API 對舊 Web／App 相容：舊購物車結帳端點、直接購買、直接預購都保留；舊客戶端用統一結帳但沒帶運費時會被要求重新核對金額（409），不會默默多收。

## 每個 migration 的切換前檢查

1. 備份正式 DB，在隔離環境還原並套用該 migration，確認 `flyway_schema_history` 成功、既有資料不變（V22：既有批次／訂單變為 FULL；V23：既有訂單 `shipping_twd=0`、沒有出貨單）。
2. V23 會新增 `shipping_method` 預設測試費率（宅配 120／滿 3000、超商 60／滿 1500）：上線前在「配送方式與運費」改成正式費率，或先停用尚未準備好的方式。
3. 權限：V22 新增 256–257、V23 新增 258–263，只自動給管理員角色（V23 的讀取權限也給一般員工）；其他角色需要時在存取管理指派。
4. 舊會員清理包（`work/cleanup-20261118/`）如需再執行，要納入 `checkout_group*`、`customer_cart_preorder`、`shipment*`。

## 上線後 smoke（每一列合併後）

- 列 3：訂單中心篩選／排序／分頁、App 訂單分頁。
- 列 4：現貨＋預購同一購物車結帳拆單、任一失敗全部回滾、訪客購物車登入合併、Buy Now。
- 列 5：建立訂金批次 → 付訂金 → 登記到貨 → 通知尾款 → 付尾款 → 分配；延期後顧客看到異動。
- 列 6：宅配／超商、分開／一起寄運費；一起寄等預購到貨後整張出貨與送達。

## 仍是模擬／測試（不可宣稱正式完成）

金流（模擬＋綠界測試環境）、物流與超商門市（測試 adapter、`MOCK-` 貨件號、5 間測試門市）、通知（outbox＋本機 MOCK）、運費預設值。詳見 `docs/CLOUD_LOCAL_FOLLOWUP.md`。

## 已知非阻斷待辦

- 直接購買／直接預購仍為宅配免運、不建立出貨單。
- 尾款逾期政策、部分到貨／部分退款／部分出貨、退貨寄回運費。
- 訂單中心大量資料 `EXPLAIN` 效能驗證。
- `StaffAccessIntegrationTest` 只讀前 50 個角色，長期使用的本機測試 DB 角色超過 50 時會失敗（CI 用新 DB 不受影響）；之後改為依 ID 查詢。
- 本機有 8 項通知測試在非空 DB／本機旗標下失敗（既有，CI 通過）。
