-- Commerce V2 Phase 7：員工 TOTP 雙因素（自願啟用）、營運報表與低庫存、營運狀態。只新增；V1–V29 不改。

-- TOTP 密鑰以 AES-GCM 加密保存（金鑰 MFA_ENCRYPTION_KEY 只在私有環境）；資料庫外洩不等於密鑰外洩。
CREATE TABLE staff_mfa (
 user_id INT PRIMARY KEY,
 secret_enc VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 confirmed_at DATETIME(6) NULL,
 last_step BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(user_id) REFERENCES user(id),
 CONSTRAINT ck_staff_mfa CHECK(enabled=FALSE OR confirmed_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE staff_mfa_event (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 user_id INT NOT NULL,
 action VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(user_id) REFERENCES user(id),
 CONSTRAINT ck_staff_mfa_event CHECK(action IN ('SETUP','ENABLE','DISABLE','LOGIN_OK','LOGIN_FAIL')),
 INDEX ix_staff_mfa_event(user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO permissions_backend(id,name,description,parent_id,level,url,method,admin_router,code) VALUES
 (282,'營運報表','銷售統計（模擬與真實分開）與排行',NULL,1,'/api/admin/reports/sales','GET','/admin/reports','reports.sales'),
 (283,'低庫存提醒','可售量低於門檻的款式',NULL,1,'/api/admin/reports/low-stock','GET','/admin/low-stock','reports.low-stock'),
 (284,'營運狀態','資料庫、Redis、通知佇列、待處理項目與錯誤統計',NULL,1,'/api/admin/ops/status','GET','/admin/ops','ops.status');
INSERT INTO role_permission VALUES(1,282),(1,283),(1,284),(2,283);
