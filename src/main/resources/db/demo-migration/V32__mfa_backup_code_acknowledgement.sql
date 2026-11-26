-- 備援碼保存確認：具 access.mfa.reset 權限的員工（系統管理員／店長）綁定或重新產生備援碼後，
-- 必須在頁面觸發下載並勾選保存確認，才能使用後台業務 API。只新增；V1–V31 不改。
-- 伺服器無法驗證檔案是否確實保存；這個欄位只記錄「使用者在頁面上完成下載與確認的操作」。
ALTER TABLE staff_mfa
 ADD backup_ack_pending BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE staff_mfa_event
 DROP CHECK ck_staff_mfa_event_v31,
 ADD CONSTRAINT ck_staff_mfa_event_v32 CHECK(action IN ('SETUP','ENABLE','DISABLE','LOGIN_OK','LOGIN_FAIL','ENROLL','BACKUP_USED','BACKUP_REGEN','RESET','BACKUP_ACK','BACKUP_SKIP'));
