-- liquibase formatted sql

-- changeset guardfind:property-operation-pages-1
-- comment 将看房预约和管家列表拆为独立页面，保留原有操作权限和角色、租户套餐授权
INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden) VALUES
(9430,'看房预约',9400,2,'/guardfind/bookings','GuardfindBookings','guardfind/bookings/index','calendar',NULL,2,1,1,NOW(),b'0',b'0',b'0'),
(9435,'管家列表',9400,2,'/guardfind/managers','GuardfindManagers','guardfind/managers/index','team',NULL,3,1,1,NOW(),b'0',b'0',b'0');

UPDATE sys_menu SET parent_id=9430 WHERE id IN (9447,9448) AND deleted=0;
UPDATE sys_menu SET parent_id=9435 WHERE id IN (9443,9444) AND deleted=0;
UPDATE sys_menu SET sort=4 WHERE id=9420 AND deleted=0;

INSERT IGNORE INTO sys_role_menu (role_id,menu_id,tenant_id)
SELECT DISTINCT role_id,9430,tenant_id FROM sys_role_menu WHERE menu_id IN (9447,9448);
INSERT IGNORE INTO sys_role_menu (role_id,menu_id,tenant_id)
SELECT DISTINCT role_id,9435,tenant_id FROM sys_role_menu WHERE menu_id IN (9443,9444);
INSERT IGNORE INTO tenant_package_menu (package_id,menu_id)
SELECT DISTINCT package_id,9430 FROM tenant_package_menu WHERE menu_id IN (9447,9448);
INSERT IGNORE INTO tenant_package_menu (package_id,menu_id)
SELECT DISTINCT package_id,9435 FROM tenant_package_menu WHERE menu_id IN (9443,9444);
