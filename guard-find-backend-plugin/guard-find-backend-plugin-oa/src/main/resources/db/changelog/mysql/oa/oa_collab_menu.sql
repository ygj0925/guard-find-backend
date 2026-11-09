--liquibase formatted sql

--changeset guardfind:oa-collab-menu-1
--comment: OA 我的日程与工作报告菜单；部门日程发布与报告汇总权限只进租户套餐，需单独授予部门负责人或行政/人事角色

INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9775,'我的日程',9500,2,'/oa/calendar','OaCalendar','oa/calendar/index','schedule',NULL,13,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9776,'list',9775,3,'system:oa:calendar:mine',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9777,'dept',9775,3,'system:oa:calendar:dept',1,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9780,'工作报告',9500,2,'/oa/report','OaReport','oa/report/index','file-text',NULL,14,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9781,'list',9780,3,'system:oa:report:mine',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9782,'stat',9780,3,'system:oa:report:stat',1,1,1,NOW());

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, new_menu.id, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9775 AS id UNION ALL SELECT 9776 UNION ALL SELECT 9780 UNION ALL SELECT 9781) new_menu;

INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, new_menu.id
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9775 AS id UNION ALL SELECT 9776 UNION ALL SELECT 9777 UNION ALL SELECT 9780 UNION ALL SELECT 9781
             UNION ALL SELECT 9782) new_menu;
