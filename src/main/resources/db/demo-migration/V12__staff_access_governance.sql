ALTER TABLE user ADD COLUMN access_version INT NOT NULL DEFAULT 0,
 ADD COLUMN auth_version INT NOT NULL DEFAULT 0,
 ADD CONSTRAINT ck_staff_versions CHECK(access_version>=0 AND auth_version>=0);
ALTER TABLE role ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE,
 ADD COLUMN version INT NOT NULL DEFAULT 0,
 ADD CONSTRAINT ck_role_version CHECK(version>=0);
CREATE TABLE staff_access_guard(id INT PRIMARY KEY, CONSTRAINT ck_access_guard CHECK(id=1));
INSERT INTO staff_access_guard VALUES(1);
CREATE TABLE staff_access_event (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 actor_id INT NOT NULL, actor_username VARCHAR(64) NOT NULL,
 action VARCHAR(32) NOT NULL, target_id INT NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 before_state JSON NULL, after_state JSON NOT NULL, note VARCHAR(500) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(actor_id) REFERENCES user(id), INDEX ix_access_event_created(created_at,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
UPDATE permissions_backend SET code='workspace.read' WHERE id=1;
UPDATE permissions_backend SET admin_router=NULL,code=CASE id WHEN 2 THEN 'legacy.staff.read' WHEN 3 THEN 'legacy.roles.read' WHEN 4 THEN 'legacy.permissions.read' END WHERE id IN(2,3,4);
INSERT INTO permissions_backend(id,name,description,parent_id,level,url,method,admin_router,code) VALUES
 (100,'員工管理','唯讀員工與角色指派',NULL,1,'/api/admin/access/staff','GET','/admin/account','access.staff.read'),
 (101,'新增員工','需完整存取管理權限',NULL,1,'/api/admin/access/staff','POST',NULL,'access.staff.create'),
 (102,'管理員工','啟停與角色指派；需完整管理權限',NULL,1,'/api/admin/access/staff/*','PUT',NULL,'access.staff.update'),
 (103,'角色管理','唯讀角色',NULL,1,'/api/admin/access/roles','GET','/admin/roles','access.roles.read'),
 (104,'新增角色','需完整存取管理權限',NULL,1,'/api/admin/access/roles','POST',NULL,'access.roles.create'),
 (105,'管理角色','啟停與固定權限指派；需完整管理權限',NULL,1,'/api/admin/access/roles/*','PUT',NULL,'access.roles.update'),
 (106,'權限目錄','程式固定的權限白名單',NULL,1,'/api/admin/access/permissions','GET','/admin/permissions','access.permissions.read'),
 (107,'存取稽核','唯讀員工與角色異動',NULL,1,'/api/admin/access/audit','GET','/admin/access/audit','access.audit.read');
INSERT INTO role_permission VALUES(1,100),(1,101),(1,102),(1,103),(1,104),(1,105),(1,106),(1,107);
