--liquibase formatted sql

--changeset guardfind:oa-cc-menu-1
--comment: OA 抄送我的菜单；详情页为隐藏路由，授权沿用已有 OA 授权角色

INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9670,'抄送我的',9500,2,'/oa/cc','OaCc','oa/cc/index','mail',NULL,4,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9671,'抄送详情',9670,2,'/oa/cc/detail','OaCcDetail','oa/cc/detail/index','mail',NULL,2,1,1,NOW(),b'0',b'0',b'1');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9672,'list',9670,3,'system:oa:cc:list',0,1,1,NOW());

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, new_menu.id, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9670 AS id UNION ALL SELECT 9671 UNION ALL SELECT 9672) new_menu;

INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, new_menu.id
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9670 AS id UNION ALL SELECT 9671 UNION ALL SELECT 9672) new_menu;
