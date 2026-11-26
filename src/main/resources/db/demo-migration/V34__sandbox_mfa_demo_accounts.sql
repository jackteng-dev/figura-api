-- 公開沙盒「MFA 展示」短效臨時帳號的到期紀錄。只新增空表，不寫入任何資料。
-- 正式環境（SHOP_SANDBOX_MODE 未開）永遠不會寫入這張表；建立帳號的 API 在正式環境回 404。
CREATE TABLE sandbox_demo_account (
 user_id INT PRIMARY KEY,
 expires_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_sandbox_demo_user FOREIGN KEY(user_id) REFERENCES user(id),
 INDEX ix_sandbox_demo_expires(expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
