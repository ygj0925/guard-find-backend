--liquibase formatted sql

--changeset guardfind:oa-staff-version
--comment: 员工与岗位增加乐观锁版本，供后台编辑并发控制

ALTER TABLE `oa_employee` ADD COLUMN `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本' AFTER `leave_date`;
ALTER TABLE `oa_position` ADD COLUMN `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本' AFTER `sort`;


--changeset guardfind:oa-staff-manage-menu-1
--comment: OA 员工与岗位管理菜单及权限；授权沿用具备用户管理权限的角色和租户套餐

INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9630,'员工管理',9500,2,'/oa/employee','OaEmployee','oa/employee/index','team',NULL,5,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9640,'岗位管理',9500,2,'/oa/position','OaPosition','oa/position/index','idcard',NULL,6,1,1,NOW(),b'0',b'0',b'0');

INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9631,'list',9630,3,'system:oa:employee:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9632,'create',9630,3,'system:oa:employee:create',1,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9633,'update',9630,3,'system:oa:employee:update',2,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9641,'list',9640,3,'system:oa:position:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9642,'create',9640,3,'system:oa:position:create',1,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9643,'update',9640,3,'system:oa:position:update',2,1,1,NOW());

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, new_menu.id, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9630 AS id UNION ALL SELECT 9631 UNION ALL SELECT 9632 UNION ALL SELECT 9633
             UNION ALL SELECT 9640 UNION ALL SELECT 9641 UNION ALL SELECT 9642 UNION ALL SELECT 9643) new_menu;

INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, new_menu.id
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9630 AS id UNION ALL SELECT 9631 UNION ALL SELECT 9632 UNION ALL SELECT 9633
             UNION ALL SELECT 9640 UNION ALL SELECT 9641 UNION ALL SELECT 9642 UNION ALL SELECT 9643) new_menu;
