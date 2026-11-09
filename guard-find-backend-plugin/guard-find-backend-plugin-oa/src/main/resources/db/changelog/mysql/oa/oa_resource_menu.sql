--liquibase formatted sql

--changeset guardfind:oa-resource-menu-1
--comment: OA 会议室管理、车辆管理、会议预订与我的用车菜单；车辆调度权限只进租户套餐，需单独授予行政调度角色

INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9760,'会议室管理',9500,2,'/oa/meeting','OaMeeting','oa/meeting/index','bank',NULL,18,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9761,'list',9760,3,'system:oa:meeting:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9762,'manage',9760,3,'system:oa:meeting:manage',1,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9765,'车辆管理',9500,2,'/oa/vehicle','OaVehicle','oa/vehicle/index','car',NULL,19,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9766,'list',9765,3,'system:oa:vehicle:list',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9767,'manage',9765,3,'system:oa:vehicle:manage',1,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9768,'dispatch',9765,3,'system:oa:vehicle:dispatch',2,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9770,'会议预订',9500,2,'/oa/meeting/book','OaMeetingBook','oa/meeting/book/index','calendar',NULL,11,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9771,'list',9770,3,'system:oa:meeting:book',0,1,1,NOW());
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9772,'我的用车',9500,2,'/oa/vehicle/mine','OaVehicleMine','oa/vehicle/mine/index','environment',NULL,12,1,1,NOW(),b'0',b'0',b'0');
INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9773,'list',9772,3,'system:oa:vehicle:mine',0,1,1,NOW());

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, new_menu.id, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9760 AS id UNION ALL SELECT 9761 UNION ALL SELECT 9762 UNION ALL SELECT 9765 UNION ALL SELECT 9766
             UNION ALL SELECT 9767 UNION ALL SELECT 9770 UNION ALL SELECT 9771 UNION ALL SELECT 9772
             UNION ALL SELECT 9773) new_menu;

INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, new_menu.id
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0 AND m.permission = 'system:user:list'
 CROSS JOIN (SELECT 9760 AS id UNION ALL SELECT 9761 UNION ALL SELECT 9762 UNION ALL SELECT 9765 UNION ALL SELECT 9766
             UNION ALL SELECT 9767 UNION ALL SELECT 9768 UNION ALL SELECT 9770 UNION ALL SELECT 9771
             UNION ALL SELECT 9772 UNION ALL SELECT 9773) new_menu;
