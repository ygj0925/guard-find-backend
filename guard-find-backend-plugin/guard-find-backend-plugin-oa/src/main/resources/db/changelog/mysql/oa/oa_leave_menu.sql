--liquibase formatted sql

--changeset guardfind:oa-leave-menu-1
--comment: OA 假期管理（类型/日历/余额/模板）与我的假期菜单；授权沿用已有 OA 授权角色

INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9680,'假期管理',9500,2,'/oa/leave','OaLeave','oa/leave/index','calendar',NULL,9,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9681,'list',9680,3,'system:oa:leave:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9682,'manage',9680,3,'system:oa:leave:manage',1,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9685,'我的假期',9500,2,'/oa/leave/mine','OaLeaveMine','oa/leave/mine/index','schedule',NULL,4,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9686,'list',9685,3,'system:oa:leave:mine',0,1,1,NOW());

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, new_menu.id, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9680 AS id UNION ALL SELECT 9681 UNION ALL SELECT 9682 UNION ALL SELECT 9685 UNION ALL SELECT 9686) new_menu;

INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, new_menu.id
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9680 AS id UNION ALL SELECT 9681 UNION ALL SELECT 9682 UNION ALL SELECT 9685 UNION ALL SELECT 9686) new_menu;
