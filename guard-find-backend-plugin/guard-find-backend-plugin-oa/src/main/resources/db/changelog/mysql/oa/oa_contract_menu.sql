--liquibase formatted sql

--changeset guardfind:oa-contract-document-menu-1
--comment: OA 合同管理、用印管理、公文知识、我的合同用印与公文阅读菜单；签署归档与用印执行权限只进租户套餐，需单独授予法务/行政与印章保管人角色

INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9740,'合同管理',9500,2,'/oa/contract','OaContract','oa/contract/index','file-protect',NULL,15,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9741,'list',9740,3,'system:oa:contract:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9742,'manage',9740,3,'system:oa:contract:manage',1,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9743,'execute',9740,3,'system:oa:contract:execute',2,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9745,'用印管理',9500,2,'/oa/seal','OaSeal','oa/seal/index','safety-certificate',NULL,16,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9746,'list',9745,3,'system:oa:seal:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9747,'manage',9745,3,'system:oa:seal:manage',1,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9748,'execute',9745,3,'system:oa:seal:execute',2,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9750,'公文知识',9500,2,'/oa/document','OaDocument','oa/document/index','read',NULL,17,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9751,'list',9750,3,'system:oa:document:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9752,'manage',9750,3,'system:oa:document:manage',1,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9755,'我的合同用印',9500,2,'/oa/contract/mine','OaContractMine','oa/contract/mine/index','solution',NULL,9,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9756,'list',9755,3,'system:oa:contract:mine',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9757,'公文阅读',9500,2,'/oa/document/inbox','OaDocumentInbox','oa/document/inbox/index','notification',NULL,10,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9758,'list',9757,3,'system:oa:document:read',0,1,1,NOW());

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, new_menu.id, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9740 AS id UNION ALL SELECT 9741 UNION ALL SELECT 9742 UNION ALL SELECT 9745 UNION ALL SELECT 9746
             UNION ALL SELECT 9747 UNION ALL SELECT 9750 UNION ALL SELECT 9751 UNION ALL SELECT 9752 UNION ALL SELECT 9755
             UNION ALL SELECT 9756 UNION ALL SELECT 9757 UNION ALL SELECT 9758) new_menu;

INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, new_menu.id
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9740 AS id UNION ALL SELECT 9741 UNION ALL SELECT 9742 UNION ALL SELECT 9743 UNION ALL SELECT 9745
             UNION ALL SELECT 9746 UNION ALL SELECT 9747 UNION ALL SELECT 9748 UNION ALL SELECT 9750 UNION ALL SELECT 9751
             UNION ALL SELECT 9752 UNION ALL SELECT 9755 UNION ALL SELECT 9756 UNION ALL SELECT 9757
             UNION ALL SELECT 9758) new_menu;
