--liquibase formatted sql

--changeset guardfind:oa-purchase-asset-menu-1
--comment: OA 采购管理（采购台账、部门预算、模板）、资产用品（用品/资产台账、领用、报废、盘点、模板）与我的采购资产菜单；采购执行与资产操作权限只进租户套餐，需单独授予采购/行政角色

INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9720,'采购管理',9500,2,'/oa/purchase','OaPurchase','oa/purchase/index','shopping-cart',NULL,13,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9721,'list',9720,3,'system:oa:purchase:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9722,'manage',9720,3,'system:oa:purchase:manage',1,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9723,'execute',9720,3,'system:oa:purchase:execute',2,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9730,'资产用品',9500,2,'/oa/asset','OaAsset','oa/asset/index','database',NULL,14,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9731,'list',9730,3,'system:oa:asset:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9732,'manage',9730,3,'system:oa:asset:manage',1,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9733,'operate',9730,3,'system:oa:asset:operate',2,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9735,'我的采购资产',9500,2,'/oa/asset/mine','OaAssetMine','oa/asset/mine/index','inbox',NULL,8,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9736,'list',9735,3,'system:oa:asset:mine',0,1,1,NOW());

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, new_menu.id, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9720 AS id UNION ALL SELECT 9721 UNION ALL SELECT 9722 UNION ALL SELECT 9730 UNION ALL SELECT 9731
             UNION ALL SELECT 9732 UNION ALL SELECT 9735 UNION ALL SELECT 9736) new_menu;

INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, new_menu.id
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9720 AS id UNION ALL SELECT 9721 UNION ALL SELECT 9722 UNION ALL SELECT 9723 UNION ALL SELECT 9730
             UNION ALL SELECT 9731 UNION ALL SELECT 9732 UNION ALL SELECT 9733 UNION ALL SELECT 9735
             UNION ALL SELECT 9736) new_menu;
