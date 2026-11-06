--liquibase formatted sql

--changeset guardfind:oa-process-menu-1
--comment: OA 流程模型菜单与发布权限；设计器作为隐藏路由，避免被 RouteAccess 拦截

INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9500,'流程管理',0,1,'/oa','Oa','Layout','apartment',NULL,30,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9510,'流程模型',9500,2,'/oa/process','OaProcess','oa/process/index','partition',NULL,1,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9520,'流程设计器',9510,2,'/oa/process/design','OaProcessDesign','oa/process/design/index','edit',NULL,2,1,1,NOW(),b'0',b'0',b'1');

INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9530,'list',9510,3,'system:oa:process:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9531,'create',9510,3,'system:oa:process:create',1,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9532,'update',9510,3,'system:oa:process:update',2,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9533,'publish',9510,3,'system:oa:process:publish',3,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9534,'disable',9510,3,'system:oa:process:disable',4,1,1,NOW());

-- 沿用已具备用户管理权限的角色与租户套餐，避免新菜单对所有租户默认开放
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, 9500, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, 9510, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, 9520, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, 9530, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, 9531, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, 9532, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, 9533, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, 9534, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';

INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, 9500
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, 9510
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, 9520
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, 9530
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, 9531
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, 9532
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, 9533
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, 9534
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
