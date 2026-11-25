-- 員工後台強制雙因素：一次性備援碼、MFA 重設稽核與權限。只新增；V1–V30 不改。
-- 既有員工不需資料遷移：下次登入時於密碼驗證後強制進入綁定流程；部署前發出的 session 因缺少 MFA 標記而失效。

-- 備援碼只存 HMAC-SHA256（金鑰衍生自 MFA_ENCRYPTION_KEY），原文只在產生當下顯示一次。
CREATE TABLE staff_mfa_backup_code (
 user_id INT NOT NULL,
 code_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 used_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(user_id,code_hash),
 FOREIGN KEY(user_id) REFERENCES user(id),
 INDEX ix_backup_unused(user_id,used_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE staff_mfa_event
 ADD actor_id INT NULL,
 ADD note VARCHAR(500) NULL,
 ADD FOREIGN KEY(actor_id) REFERENCES user(id),
 DROP CHECK ck_staff_mfa_event,
 ADD CONSTRAINT ck_staff_mfa_event_v31 CHECK(action IN ('SETUP','ENABLE','DISABLE','LOGIN_OK','LOGIN_FAIL','ENROLL','BACKUP_USED','BACKUP_REGEN','RESET'));

INSERT INTO permissions_backend(id,name,description,parent_id,level,url,method,admin_router,code) VALUES
 (285,'重設員工雙因素','員工遺失驗證器與備援碼時重設，對方下次登入需重新綁定',NULL,1,'/api/admin/access/staff/*/mfa/reset','POST',NULL,'access.mfa.reset');
INSERT INTO role_permission VALUES(1,285);
