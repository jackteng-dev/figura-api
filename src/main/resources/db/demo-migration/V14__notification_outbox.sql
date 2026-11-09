CREATE TABLE notification_outbox (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 event_kind VARCHAR(40) NOT NULL,
 source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, recipient_ref CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payload JSON NOT NULL,
 status VARCHAR(12) NOT NULL DEFAULT 'PENDING', version INT NOT NULL DEFAULT 0,
 attempts INT NOT NULL DEFAULT 0, cycle_attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NULL DEFAULT CURRENT_TIMESTAMP(6),
 last_error VARCHAR(32) NULL, sent_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_notification_source(event_kind,source_id),
 FOREIGN KEY(order_id) REFERENCES customer_order(id),
 FOREIGN KEY(recipient_ref) REFERENCES customer(id),
 CONSTRAINT ck_notification_status CHECK(status IN ('PENDING','RETRY','SENT','DEAD')),
 CONSTRAINT ck_notification_counts CHECK(version>=0 AND attempts>=cycle_attempts AND cycle_attempts BETWEEN 0 AND 5),
 CONSTRAINT ck_notification_due CHECK((status IN ('PENDING','RETRY') AND next_attempt_at IS NOT NULL AND sent_at IS NULL) OR (status='SENT' AND next_attempt_at IS NULL AND sent_at IS NOT NULL) OR (status='DEAD' AND next_attempt_at IS NULL AND sent_at IS NULL)),
 INDEX ix_notification_due(status,next_attempt_at,id), INDEX ix_notification_order(order_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- An independent durable MOCK provider, deliberately without parent FKs. It may accept while
-- the caller transaction later rolls back; deliveryKey=id deduplicates that unknown result.
CREATE TABLE mock_notification_receipt (
 delivery_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 accepted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE notification_event (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 notification_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_id INT NULL, actor_username VARCHAR(64) NOT NULL,
 action VARCHAR(16) NOT NULL, mode VARCHAR(24) NULL, note VARCHAR(500) NOT NULL,
 expected_version INT NOT NULL, before_status VARCHAR(12) NOT NULL, after_status VARCHAR(12) NOT NULL,
 after_version INT NOT NULL, attempt_number INT NOT NULL,
 result VARCHAR(24) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(notification_id) REFERENCES notification_outbox(id), FOREIGN KEY(actor_id) REFERENCES user(id),
 CONSTRAINT ck_notification_action CHECK(action IN ('DISPATCH','RETRY')),
 CONSTRAINT ck_notification_mode CHECK(mode IS NULL OR mode IN ('SUCCESS','FAIL_BEFORE','FAIL_AFTER_ACCEPT')),
 INDEX ix_notification_event(notification_id,created_at,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO permissions_backend(id,name,description,parent_id,level,url,method,admin_router,code) VALUES
 (220,'訂單通知','唯讀通知佇列與mock結果',NULL,1,'/api/admin/notifications','GET','/admin/notifications','notifications.read'),
 (221,'通知詳情','快照與唯讀操作紀錄',NULL,1,'/api/admin/notifications/*','GET',NULL,'notifications.detail'),
 (222,'模擬通知派送','只用本地mock provider',NULL,1,'/api/admin/notifications/*/mock-dispatch','POST',NULL,'notifications.dispatch.mock'),
 (223,'通知重新排程','失敗通知版本核對及稽核',NULL,1,'/api/admin/notifications/*/retry','POST',NULL,'notifications.retry');
INSERT INTO role_permission VALUES(1,220),(1,221),(1,222),(1,223),(2,220),(2,221);
