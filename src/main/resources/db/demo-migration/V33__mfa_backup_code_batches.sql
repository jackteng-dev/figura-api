-- 備援碼批次：保存確認必須對應「目前」這一批，舊批次的確認不能解除新批次；
-- 是否需要確認改為依目前權限即時判斷（具 access.mfa.reset 且已確認批次 ≠ 目前批次），員工被升權後也會要求保存。
-- 只新增；V1–V32 不改。V32 的 backup_ack_pending 自此不再使用（保留欄位以維持已套用 migration 的 checksum）。
ALTER TABLE staff_mfa
 ADD backup_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD backup_confirmed_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
