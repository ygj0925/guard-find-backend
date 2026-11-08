--liquibase formatted sql

--changeset guardfind:oa-ops-menu-1
--comment: OA 通知与运维菜单、催办与终止权限；运维终止与审批动作分离授权

INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9650,'OA 运维',9500,2,'/oa/operations','OaOperations','oa/operations/index','tool',NULL,7,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9660,'通知消息',9500,2,'/oa/notifications','OaNotifications','oa/notifications/index','notification',NULL,8,1,1,NOW(),b'0',b'0',b'0');

INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9605,'urge',9600,3,'system:oa:application:urge',2,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9651,'list',9650,3,'system:oa:ops:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9652,'outbox retry',9650,3,'system:oa:ops:outbox:retry',1,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9653,'terminate',9650,3,'system:oa:ops:terminate',2,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9661,'list',9660,3,'system:oa:notification:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9662,'read',9660,3,'system:oa:notification:read',1,1,1,NOW());

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, new_menu.id, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9604 AS id UNION ALL SELECT 9650 UNION ALL SELECT 9651 UNION ALL SELECT 9652
             UNION ALL SELECT 9653 UNION ALL SELECT 9660 UNION ALL SELECT 9661 UNION ALL SELECT 9662) new_menu;

INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, new_menu.id
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9604 AS id UNION ALL SELECT 9650 UNION ALL SELECT 9651 UNION ALL SELECT 9652
             UNION ALL SELECT 9653 UNION ALL SELECT 9660 UNION ALL SELECT 9661 UNION ALL SELECT 9662) new_menu;

--changeset guardfind:oa-ops-menu-2
--comment: 修正催办菜单 9605 的角色与套餐授权（首版授权列表误写为 9604）

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, 9605, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list';

INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, 9605
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list';
