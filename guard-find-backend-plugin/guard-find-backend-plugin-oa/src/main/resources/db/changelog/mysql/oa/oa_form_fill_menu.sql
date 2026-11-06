--liquibase formatted sql

--changeset guardfind:oa-form-fill-menu-1
--comment: OA 表单填写页隐藏路由；权限沿用表单 list

INSERT INTO sys_menu (id,title,parent_id,type,path,name,component,icon,permission,sort,status,create_user,create_time,is_external,is_cache,is_hidden)
VALUES (9580,'表单填写',9550,2,'/oa/form/fill','OaFormFill','oa/form/fill/index','form',NULL,3,1,1,NOW(),b'0',b'0',b'1');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, 9580, rm.tenant_id
  FROM sys_role_menu rm
 WHERE rm.menu_id = 9570;
INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, 9580
  FROM tenant_package_menu tpm
 WHERE tpm.menu_id = 9570;
