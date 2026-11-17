-- V19：顧客以手機＋信箱＋密碼註冊、信箱驗證與忘記密碼。只新增，不改 V1–V18。
-- 新舊版程式都能運作：新欄位皆可為 NULL（舊版寫入顧客時不填），新表只有新版使用。
-- 舊帳號（只有 username）保留原登入方式；新註冊會自動產生內部 username 與預設暱稱。

ALTER TABLE customer
  ADD COLUMN email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER display_name,
  ADD COLUMN phone VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER email,
  ADD COLUMN email_verified_at DATETIME(6) NULL AFTER phone,
  ADD CONSTRAINT uk_customer_email UNIQUE (email),
  ADD CONSTRAINT uk_customer_phone UNIQUE (phone);

-- 信箱驗證與重設密碼的一次性連結。只存 token 的 SHA-256，不存原文。
CREATE TABLE customer_email_token (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  purpose VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  used_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uk_customer_email_token_hash UNIQUE (token_hash),
  CONSTRAINT fk_customer_email_token_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
  CONSTRAINT ck_customer_email_token_purpose CHECK (purpose IN ('VERIFY','RESET')),
  INDEX idx_customer_email_token_owner (customer_id, purpose, used_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 模擬寄信：只在 MOCK_EMAIL_ENABLED=true 時寫入，不實際寄出。
-- 連結含一次性 token，只供有通知權限的員工在後台檢視（展示用途）；接上真實寄信服務後不再寫入。
CREATE TABLE customer_mock_email (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  purpose VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  recipient VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  subject VARCHAR(120) NOT NULL,
  link VARCHAR(300) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_customer_mock_email_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
  INDEX idx_customer_mock_email_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
