--liquibase formatted sql

--changeset guardfind:oa-stats-menu-1
--comment: OA 工作台与统计报表菜单；工作台随用户管理角色下发，统计查看与导出只进租户套餐，需单独授予管理/行政/财务角色

INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9790,'OA 工作台',9500,2,'/oa/workbench','OaWorkbench','oa/workbench/index','dashboard',NULL,0,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9791,'view',9790,3,'system:oa:workbench:view',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9795,'统计报表',9500,2,'/oa/stats','OaStats','oa/stats/index','bar-chart',NULL,30,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9796,'list',9795,3,'system:oa:stats:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9797,'export',9795,3,'system:oa:stats:export',1,1,1,NOW());

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, new_menu.id, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9790 AS id UNION ALL SELECT 9791) new_menu;

INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, new_menu.id
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9790 AS id UNION ALL SELECT 9791 UNION ALL SELECT 9795 UNION ALL SELECT 9796
             UNION ALL SELECT 9797) new_menu;
