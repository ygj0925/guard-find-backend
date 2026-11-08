--liquibase formatted sql

--changeset guardfind:oa-attendance-menu-1
--comment: OA 假勤管理（班次、排班、考勤记录、月度汇总、补卡加班外出单、模板）与我的考勤菜单；考勤导入权限只进租户套餐，需单独授予人事角色

INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9710,'假勤管理',9500,2,'/oa/attendance','OaAttendance','oa/attendance/index','calendar',NULL,12,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9711,'list',9710,3,'system:oa:attendance:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9712,'manage',9710,3,'system:oa:attendance:manage',1,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9713,'import',9710,3,'system:oa:attendance:import',2,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9715,'我的考勤',9500,2,'/oa/attendance/mine','OaAttendanceMine','oa/attendance/mine/index','schedule',NULL,7,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9716,'list',9715,3,'system:oa:attendance:mine',0,1,1,NOW());

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, new_menu.id, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9710 AS id UNION ALL SELECT 9711 UNION ALL SELECT 9712 UNION ALL SELECT 9715 UNION ALL SELECT 9716) new_menu;

INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, new_menu.id
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9710 AS id UNION ALL SELECT 9711 UNION ALL SELECT 9712 UNION ALL SELECT 9713 UNION ALL SELECT 9715 UNION ALL SELECT 9716) new_menu;
