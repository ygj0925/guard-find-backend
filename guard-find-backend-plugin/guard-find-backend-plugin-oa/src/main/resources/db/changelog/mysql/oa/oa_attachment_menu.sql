--liquibase formatted sql

--changeset guardfind:oa-attachment-menu-1
--comment: OA 附件下载按钮权限；上传复用文件管理的 system:file:upload，只授予可发起流程的角色

INSERT INTO sys_menu (id,title,parent_id,type,permission,sort,status,create_user,create_time)
VALUES (9604,'download',9600,3,'system:oa:attachment:download',2,1,1,NOW());

-- 申请人与审批人都需要下载附件
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, 9604, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0
 WHERE m.permission IN ('system:oa:launch:submit','system:oa:approval:list','system:oa:application:list');

-- 附件上传复用文件管理上传权限（菜单 1113），只授予可发起流程的角色
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, tenant_id)
SELECT DISTINCT rm.role_id, 1113, rm.tenant_id
  FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0 AND m.permission = 'system:oa:launch:submit';

INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, 9604
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0
 WHERE m.permission IN ('system:oa:launch:submit','system:oa:approval:list','system:oa:application:list');

INSERT IGNORE INTO tenant_package_menu (package_id, menu_id)
SELECT DISTINCT tpm.package_id, 1113
  FROM tenant_package_menu tpm
  JOIN sys_menu m ON m.id = tpm.menu_id AND m.deleted = 0 AND m.permission = 'system:oa:launch:submit';
