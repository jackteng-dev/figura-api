-- 僅供新建隔離 demo DB。不是使用者舊 DB migration，沒有 DROP／ALTER。
CREATE TABLE user (
  id INT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL UNIQUE,
  password VARCHAR(255) NOT NULL,
  first_name VARCHAR(64), last_name VARCHAR(64), email VARCHAR(255), telephone VARCHAR(32), user_pic VARCHAR(1000),
  role VARCHAR(8), is_stop VARCHAR(1) NOT NULL DEFAULT 'F',
  lastLogin_time DATETIME, created_time DATETIME, modified_time DATETIME
);
CREATE TABLE role (id INT PRIMARY KEY, name VARCHAR(64) NOT NULL, description VARCHAR(255));
CREATE TABLE permissions_backend (
  id INT PRIMARY KEY, name VARCHAR(64) NOT NULL, description VARCHAR(255), parent_id INT,
  level INT, url VARCHAR(255), method VARCHAR(16), admin_router VARCHAR(255)
);
CREATE TABLE user_role (
  user_id INT NOT NULL, role_id INT NOT NULL, PRIMARY KEY(user_id,role_id),
  FOREIGN KEY(user_id) REFERENCES user(id), FOREIGN KEY(role_id) REFERENCES role(id)
);
CREATE TABLE role_permission (
  role_id INT NOT NULL, permission_id INT NOT NULL, PRIMARY KEY(role_id,permission_id),
  FOREIGN KEY(role_id) REFERENCES role(id), FOREIGN KEY(permission_id) REFERENCES permissions_backend(id)
);
INSERT INTO role VALUES(1,'展示店長','員工登入基準演練'),(2,'展示員工','僅工作首頁');
INSERT INTO permissions_backend VALUES
 (1,'工作首頁','目前登入者',NULL,1,'/api/auth/me','GET',NULL),
 (2,'用戶列表','只讀帳號列表',NULL,1,'/api/admin/account','POST','/admin/account'),
 (3,'角色列表','只讀角色列表',NULL,1,'/api/admin/role','GET','/admin/roles'),
 (4,'權限列表','只讀權限列表',NULL,1,'/api/role/permission','GET','/admin/permissions');
INSERT INTO role_permission VALUES(1,1),(1,2),(1,3),(1,4),(2,1);
