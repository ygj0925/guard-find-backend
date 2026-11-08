--liquibase formatted sql

--changeset guardfind:oa-expense-menu-1
--comment: OA 报销管理（费用类型/报销台账/支付登记/模板）与我的报销菜单；支付登记权限只进租户套餐，需单独授予财务角色

INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9690,'报销管理',9500,2,'/oa/expense','OaExpense','oa/expense/index','account-book',NULL,10,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9691,'list',9690,3,'system:oa:expense:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9692,'manage',9690,3,'system:oa:expense:manage',1,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9693,'pay',9690,3,'system:oa:expense:pay',2,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9695,'我的报销',9500,2,'/oa/expense/mine','OaExpenseMine','oa/expense/mine/index','wallet',NULL,5,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9696,'list',9695,3,'system:oa:expense:mine',0,1,1,NOW());

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, new_menu.id, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9690 AS id UNION ALL SELECT 9691 UNION ALL SELECT 9692 UNION ALL SELECT 9695 UNION ALL SELECT 9696) new_menu;

INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, new_menu.id
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9690 AS id UNION ALL SELECT 9691 UNION ALL SELECT 9692 UNION ALL SELECT 9693 UNION ALL SELECT 9695 UNION ALL SELECT 9696) new_menu;
