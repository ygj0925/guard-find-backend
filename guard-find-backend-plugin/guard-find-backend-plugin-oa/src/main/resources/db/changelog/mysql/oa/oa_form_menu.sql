--liquibase formatted sql

--changeset guardfind:oa-form-menu-1
--comment: OA 表单管理菜单与权限；设计器作为隐藏路由，避免被 RouteAccess 拦截

INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9550,'表单管理',9500,2,'/oa/form','OaForm','oa/form/index','form',NULL,2,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9560,'表单设计器',9550,2,'/oa/form/design','OaFormDesign','oa/form/design/index','edit',NULL,1,1,1,NOW(),b'0',b'0',b'1');

INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9570,'list',9550,3,'system:oa:form:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9571,'create',9550,3,'system:oa:form:create',1,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9572,'update',9550,3,'system:oa:form:update',2,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9573,'publish',9550,3,'system:oa:form:publish',3,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9574,'disable',9550,3,'system:oa:form:disable',4,1,1,NOW());

-- 沿用已具备用户管理权限的角色与租户套餐，避免新菜单对所有租户默认开放
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, 9550, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, 9560, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, 9570, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, 9571, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, 9572, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, 9573, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, 9574, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';

INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, 9550
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, 9560
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, 9570
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, 9571
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, 9572
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, 9573
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, 9574
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0
 WHERE m.permission = 'system:user:list';
