-- Preorders own quota and order lifecycle; never reference sku_inventory for reservations.
CREATE TABLE preorder_order (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 batch_name VARCHAR(120) NOT NULL, product_title VARCHAR(120) NOT NULL, sku_label VARCHAR(80) NOT NULL,
 unit_price_twd INT NOT NULL, quantity INT NOT NULL, subtotal_twd BIGINT NOT NULL,
 shipping_twd INT NOT NULL DEFAULT 0, shipping JSON NOT NULL,
 deadline_at DATETIME(6) NOT NULL, arrival_from DATE NOT NULL, arrival_to DATE NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', version INT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL, expires_at DATETIME(6) NOT NULL, closed_at DATETIME(6) NULL,
 FOREIGN KEY(customer_id) REFERENCES customer(id), FOREIGN KEY(batch_id) REFERENCES preorder_batch(id),
 CONSTRAINT ck_preorder_order_amount CHECK(unit_price_twd BETWEEN 1 AND 99999999 AND quantity BETWEEN 1 AND 100 AND subtotal_twd=unit_price_twd*quantity AND shipping_twd=0),
 CONSTRAINT ck_preorder_order_state CHECK(version>=1 AND ((status='PENDING' AND closed_at IS NULL) OR (status IN ('CANCELLED','EXPIRED') AND closed_at IS NOT NULL))),
 CONSTRAINT ck_preorder_order_times CHECK(created_at<expires_at AND expires_at<=deadline_at AND expires_at<=DATE_ADD(created_at,INTERVAL 30 MINUTE) AND arrival_from<=arrival_to),
 INDEX ix_preorder_order_owner(customer_id,created_at,id), INDEX ix_preorder_order_due(status,expires_at,id), INDEX ix_preorder_order_batch(batch_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Immutable business audit and quota ledger. Request IDs are scoped to the authenticated customer.
-- SYSTEM expiry also records the owner, but is never exposed as a customer command endpoint.
CREATE TABLE preorder_order_event (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_kind VARCHAR(8) NOT NULL, action VARCHAR(8) NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 quantity INT NOT NULL, held_delta INT NOT NULL, held_before INT NOT NULL, held_after INT NOT NULL,
 paid_before INT NOT NULL, paid_after INT NOT NULL, order_version INT NOT NULL, batch_version INT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_preorder_order_request(customer_id,request_id), UNIQUE KEY uq_preorder_order_action(order_id,action),
 FOREIGN KEY(customer_id) REFERENCES customer(id), FOREIGN KEY(order_id) REFERENCES preorder_order(id), FOREIGN KEY(batch_id) REFERENCES preorder_batch(id),
 CONSTRAINT ck_preorder_event CHECK(actor_kind IN ('CUSTOMER','SYSTEM') AND action IN ('PLACE','CANCEL','EXPIRE') AND quantity BETWEEN 1 AND 100 AND held_before>=0 AND held_after>=0 AND held_after=held_before+held_delta AND paid_before>=0 AND paid_after=paid_before AND order_version>=1 AND batch_version>=1 AND ((action='PLACE' AND held_delta=quantity AND actor_kind='CUSTOMER') OR (action IN ('CANCEL','EXPIRE') AND held_delta=-quantity))),
 INDEX ix_preorder_event_order(order_id,created_at,id), INDEX ix_preorder_event_batch(batch_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Preserve all V14 rows and payloads. Exactly one typed parent is required for new and old messages.
ALTER TABLE notification_outbox
 MODIFY order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD preorder_order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER order_id,
 ADD FOREIGN KEY(preorder_order_id) REFERENCES preorder_order(id),
 ADD CONSTRAINT ck_notification_parent CHECK((order_id IS NOT NULL AND preorder_order_id IS NULL) OR (order_id IS NULL AND preorder_order_id IS NOT NULL)),
 ADD INDEX ix_notification_preorder(preorder_order_id,created_at,id);
