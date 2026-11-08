--liquibase formatted sql

--changeset guardfind:oa-travel-menu-1
--comment: OA 出差借款管理（出差/借款台账、借款发放与还款登记、模板）与我的出差借款菜单；发放还款权限只进租户套餐，需单独授予财务角色

INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9700,'出差借款',9500,2,'/oa/travel','OaTravel','oa/travel/index','car',NULL,11,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9701,'list',9700,3,'system:oa:travel:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9702,'manage',9700,3,'system:oa:travel:manage',1,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9703,'pay',9700,3,'system:oa:loan:pay',2,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9705,'我的出差借款',9500,2,'/oa/travel/mine','OaTravelMine','oa/travel/mine/index','compass',NULL,6,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9706,'list',9705,3,'system:oa:travel:mine',0,1,1,NOW());

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, new_menu.id, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9700 AS id UNION ALL SELECT 9701 UNION ALL SELECT 9702 UNION ALL SELECT 9705 UNION ALL SELECT 9706) new_menu;

INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, new_menu.id
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9700 AS id UNION ALL SELECT 9701 UNION ALL SELECT 9702 UNION ALL SELECT 9703 UNION ALL SELECT 9705 UNION ALL SELECT 9706) new_menu;
