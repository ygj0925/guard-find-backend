--liquibase formatted sql

--changeset guardfind:oa-launch-menu-1
--comment: OA 发起流程与我的申请菜单；填写页/详情页为隐藏路由，权限沿用已有 OA 授权角色

INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9590,'发起流程',9500,2,'/oa/launch','OaLaunch','oa/launch/index','check-square',NULL,3,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9591,'填写并发起',9590,2,'/oa/launch/fill','OaLaunchFill','oa/launch/fill/index','form',NULL,2,1,1,NOW(),b'0',b'0',b'1');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9592,'list',9590,3,'system:oa:launch:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9593,'submit',9590,3,'system:oa:launch:submit',1,1,1,NOW());

INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9600,'我的申请',9500,2,'/oa/application','OaApplication','oa/application/index','book',NULL,4,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9601,'申请详情',9600,2,'/oa/application/detail','OaApplicationDetail','oa/application/detail/index','book',NULL,2,1,1,NOW(),b'0',b'0',b'1');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9602,'list',9600,3,'system:oa:application:list',0,1,1,NOW());

-- 与流程/表单管理一致：只向已具备系统用户查看权限的角色和租户套餐复制授权
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, new_menu.id, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9590 AS id UNION ALL SELECT 9591 UNION ALL SELECT 9592 UNION ALL SELECT 9593
             UNION ALL SELECT 9600 UNION ALL SELECT 9601 UNION ALL SELECT 9602) new_menu;

INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, new_menu.id
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9590 AS id UNION ALL SELECT 9591 UNION ALL SELECT 9592 UNION ALL SELECT 9593
             UNION ALL SELECT 9600 UNION ALL SELECT 9601 UNION ALL SELECT 9602) new_menu;
