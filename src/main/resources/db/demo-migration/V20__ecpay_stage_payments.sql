-- V1–V19 are immutable. ECPay (stage) payments share the existing payment ledger.
-- Additive only: old code keeps inserting MOCK rows through the column default.
ALTER TABLE mock_payment
 ADD COLUMN provider VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'MOCK',
 ADD COLUMN gateway_trade_no VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD CONSTRAINT ck_payment_provider CHECK(provider IN ('MOCK','ECPAY') AND (provider='ECPAY' OR gateway_trade_no IS NULL)),
 ADD UNIQUE KEY uk_payment_gateway_trade(provider,gateway_trade_no);
