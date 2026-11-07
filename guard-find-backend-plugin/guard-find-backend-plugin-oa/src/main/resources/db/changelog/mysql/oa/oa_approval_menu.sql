--liquibase formatted sql

--changeset guardfind:oa-approval-menu-1
--comment: OA 审批中心菜单与动作权限；详情页为隐藏路由，撤回权限挂在“我的申请”下

INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9610,'待办审批',9500,2,'/oa/tasks','OaTasks','oa/tasks/index','audit',NULL,5,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9611,'审批详情',9610,2,'/oa/tasks/detail','OaTasksDetail','oa/tasks/detail/index','profile',NULL,2,1,1,NOW(),b'0',b'0',b'1');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9612,'list',9610,3,'system:oa:approval:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9613,'action',9610,3,'system:oa:approval:action',1,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9603,'withdraw',9600,3,'system:oa:application:withdraw',1,1,1,NOW());

-- 与流程/表单管理一致：只向已具备系统用户查看权限的角色和租户套餐复制授权
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, new_menu.id, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9610 AS id UNION ALL SELECT 9611 UNION ALL SELECT 9612 UNION ALL SELECT 9613
             UNION ALL SELECT 9603) new_menu;

INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, new_menu.id
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9610 AS id UNION ALL SELECT 9611 UNION ALL SELECT 9612 UNION ALL SELECT 9613
             UNION ALL SELECT 9603) new_menu;
