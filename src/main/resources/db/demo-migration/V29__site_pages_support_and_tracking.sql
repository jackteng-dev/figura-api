-- Commerce V2 Phase 6：政策／說明頁（草稿→審核→發布）、客服單、出貨追蹤事件。只新增；V1–V28 不改。
-- 政策頁只建立空白草稿，不寫入任何營運承諾；正式內容需作者撰寫並由另一位有發布權限的員工審核後才公開。

CREATE TABLE site_page (
 slug VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DRAFT',
 draft_title VARCHAR(120) NOT NULL, draft_body MEDIUMTEXT NOT NULL,
 draft_editor_id INT NULL, draft_updated_at DATETIME(6) NULL,
 published_title VARCHAR(120) NULL, published_body MEDIUMTEXT NULL,
 published_by INT NULL, published_at DATETIME(6) NULL, published_revision INT NOT NULL DEFAULT 0,
 version INT NOT NULL DEFAULT 1,
 FOREIGN KEY(draft_editor_id) REFERENCES user(id), FOREIGN KEY(published_by) REFERENCES user(id),
 CONSTRAINT ck_site_page CHECK(kind IN ('POLICY','HELP') AND status IN ('DRAFT','IN_REVIEW','PUBLISHED') AND version>=1 AND published_revision>=0
   AND ((published_revision=0)=(published_at IS NULL)) AND ((published_at IS NULL)=(published_body IS NULL)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE site_page_event (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 slug VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_id INT NOT NULL, actor_username VARCHAR(64) NOT NULL,
 action VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 note VARCHAR(500) NOT NULL, before_version INT NOT NULL, after_version INT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(slug) REFERENCES site_page(slug), FOREIGN KEY(actor_id) REFERENCES user(id),
 CONSTRAINT ck_site_page_event CHECK(action IN ('EDIT','SUBMIT','PUBLISH','REJECT','UNPUBLISH') AND after_version=before_version+1),
 INDEX ix_site_page_event(slug,created_at,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO site_page(slug,kind,draft_title,draft_body) VALUES
 ('faq','HELP','常見問題',''),
 ('preorder-policy','POLICY','預購與訂金規則',''),
 ('shipping-policy','POLICY','配送說明',''),
 ('cancellation-refund','POLICY','取消與退款',''),
 ('returns-defects','POLICY','退換貨與瑕疵處理',''),
 ('privacy','POLICY','隱私權政策',''),
 ('terms','POLICY','服務條款',''),
 ('contact','HELP','聯絡我們','');

-- 客服單：顧客本人建立；員工處理。internal 訊息只有員工看得到。
CREATE TABLE support_ticket (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 category VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 subject VARCHAR(120) NOT NULL,
 order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 preorder_order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'WAITING_STAFF',
 assignee_id INT NULL,
 version INT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(customer_id) REFERENCES customer(id), FOREIGN KEY(order_id) REFERENCES customer_order(id),
 FOREIGN KEY(preorder_order_id) REFERENCES preorder_order(id), FOREIGN KEY(assignee_id) REFERENCES user(id),
 UNIQUE KEY uq_ticket_request(customer_id,request_id),
 CONSTRAINT ck_ticket CHECK(category IN ('ORDER','PREORDER','SHIPPING','RETURN_DEFECT','PAYMENT','ACCOUNT','PRODUCT','OTHER')
   AND status IN ('WAITING_STAFF','WAITING_CUSTOMER','RESOLVED','CLOSED') AND version>=1 AND NOT (order_id IS NOT NULL AND preorder_order_id IS NOT NULL)),
 INDEX ix_ticket_owner(customer_id,updated_at,id), INDEX ix_ticket_queue(status,updated_at,id), INDEX ix_ticket_assignee(assignee_id,status,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE support_message (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 author_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL, staff_id INT NULL, staff_username VARCHAR(64) NULL,
 internal BOOLEAN NOT NULL DEFAULT FALSE,
 body VARCHAR(4000) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(ticket_id) REFERENCES support_ticket(id), FOREIGN KEY(customer_id) REFERENCES customer(id), FOREIGN KEY(staff_id) REFERENCES user(id),
 UNIQUE KEY uq_message_request(ticket_id,request_id),
 CONSTRAINT ck_message CHECK((author_type='CUSTOMER' AND customer_id IS NOT NULL AND staff_id IS NULL AND internal=FALSE)
   OR (author_type='STAFF' AND staff_id IS NOT NULL AND customer_id IS NULL)),
 INDEX ix_message_ticket(ticket_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 員工對客服單的狀態／指派稽核
CREATE TABLE support_ticket_event (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_id INT NOT NULL, actor_username VARCHAR(64) NOT NULL,
 action VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 before_status VARCHAR(16) NOT NULL, after_status VARCHAR(16) NOT NULL,
 before_assignee INT NULL, after_assignee INT NULL, note VARCHAR(500) NOT NULL,
 before_version INT NOT NULL, after_version INT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(ticket_id) REFERENCES support_ticket(id), FOREIGN KEY(actor_id) REFERENCES user(id),
 CONSTRAINT ck_ticket_event CHECK(action IN ('STATUS','ASSIGN') AND after_version=before_version+1),
 INDEX ix_ticket_event(ticket_id,created_at,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 出貨追蹤事件（測試物流 adapter；正式物流 API 接入後由 adapter 寫入同一張表）
CREATE TABLE shipment_tracking_event (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 shipment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 code VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 description VARCHAR(200) NOT NULL,
 source VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'TEST',
 actor_id INT NULL,
 occurred_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(shipment_id) REFERENCES shipment(id), FOREIGN KEY(actor_id) REFERENCES user(id),
 UNIQUE KEY uq_tracking_request(request_id),
 CONSTRAINT ck_tracking CHECK(code IN ('PICKED_UP','IN_TRANSIT','ARRIVED_STORE','OUT_FOR_DELIVERY','EXCEPTION','RETURNED') AND source='TEST'),
 INDEX ix_tracking_shipment(shipment_id,occurred_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO permissions_backend(id,name,description,parent_id,level,url,method,admin_router,code) VALUES
 (269,'頁面與政策','政策／說明頁草稿與發布狀態',NULL,1,'/api/admin/site-pages','GET','/admin/site-pages','pages.read'),
 (270,'編輯頁面草稿','編輯與送審政策／說明頁',NULL,1,'/api/admin/site-pages/*','PUT',NULL,'pages.edit'),
 (271,'送審頁面','把草稿送交審核',NULL,1,'/api/admin/site-pages/*/submit','POST',NULL,'pages.submit'),
 (272,'發布頁面','審核並發布（不可是最後編輯者）',NULL,1,'/api/admin/site-pages/*/publish','POST',NULL,'pages.publish'),
 (273,'退回頁面','審核退回草稿',NULL,1,'/api/admin/site-pages/*/reject','POST',NULL,'pages.reject'),
 (274,'客服單','客服單清單與內容',NULL,1,'/api/admin/support-tickets','GET','/admin/support-tickets','support.read'),
 (275,'客服單詳情','客服單訊息',NULL,1,'/api/admin/support-tickets/*','GET',NULL,'support.detail'),
 (276,'回覆客服單','回覆顧客或內部備註',NULL,1,'/api/admin/support-tickets/*/messages','POST',NULL,'support.reply'),
 (277,'客服單狀態','變更狀態',NULL,1,'/api/admin/support-tickets/*/status','POST',NULL,'support.status'),
 (278,'指派客服單','指派處理人員',NULL,1,'/api/admin/support-tickets/*/assign','POST',NULL,'support.assign'),
 (279,'物流追蹤（測試）','新增測試物流追蹤事件',NULL,1,'/api/admin/shipments/*/tracking','POST',NULL,'shipments.tracking.mock'),
 (280,'頁面詳情','頁面草稿、已發布版本與紀錄',NULL,1,'/api/admin/site-pages/*','GET',NULL,'pages.detail'),
 (281,'物流追蹤紀錄','出貨單的測試物流追蹤事件',NULL,1,'/api/admin/shipments/*/tracking','GET',NULL,'shipments.tracking.read');
INSERT INTO role_permission VALUES(1,269),(1,270),(1,271),(1,272),(1,273),(1,274),(1,275),(1,276),(1,277),(1,278),(1,279),(1,280),(1,281),
 (2,269),(2,270),(2,271),(2,274),(2,275),(2,276),(2,277),(2,280),(2,281);
