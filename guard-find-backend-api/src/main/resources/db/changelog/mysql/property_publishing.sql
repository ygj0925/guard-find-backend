-- liquibase formatted sql
-- changeset guardfind:property-publishing-1 dbms:mysql
CREATE TABLE gf_property_publish_account (
 id bigint PRIMARY KEY, platform varchar(30) NOT NULL, name varchar(80) NOT NULL, handle varchar(100) NOT NULL,
 mode varchar(20) NOT NULL, credential_key varchar(80) NULL, enabled boolean NOT NULL DEFAULT true,
 last_dispatch_at datetime NULL, create_time datetime NOT NULL, update_time datetime NOT NULL,
 UNIQUE KEY uk_publish_account(platform,handle)
);
CREATE TABLE gf_property_publish_config (id bigint PRIMARY KEY, auto_draft boolean NOT NULL DEFAULT false,
 account_ids longtext NOT NULL, activated_at datetime NULL);
INSERT INTO gf_property_publish_config(id,auto_draft,account_ids) VALUES(1,false,'[]');
CREATE TABLE gf_property_publish_job (
 id bigint PRIMARY KEY, property_id bigint NOT NULL, account_id bigint NOT NULL, platform varchar(30) NOT NULL,
 title varchar(200) NOT NULL, body longtext NOT NULL, images longtext NOT NULL, source_hash varchar(64) NOT NULL,
 status varchar(30) NOT NULL, version bigint NOT NULL DEFAULT 0, scheduled_at datetime NOT NULL,
 request_key varchar(200) NOT NULL, request_hash varchar(64) NOT NULL, attempts int NOT NULL DEFAULT 0,
 last_error varchar(500) NULL, result_url varchar(1000) NULL, platform_item_id varchar(500) NULL,
 approved_by bigint NULL, approved_at datetime NULL, create_time datetime NOT NULL, update_time datetime NOT NULL,
 UNIQUE KEY uk_publish_request(request_key), INDEX idx_publish_dispatch(status,scheduled_at,id),
 INDEX idx_publish_account(account_id,status), INDEX idx_publish_property(property_id,id)
);
CREATE TABLE gf_property_publish_log (
 id bigint PRIMARY KEY, job_id bigint NOT NULL, actor_id bigint NULL, action varchar(40) NOT NULL,
 note varchar(1000) NULL, create_time datetime NOT NULL, INDEX idx_publish_log(job_id,id)
);

-- changeset guardfind:property-publishing-menu-1 dbms:mysql
INSERT INTO sys_menu (`id`,`title`,`parent_id`,`type`,`path`,`name`,`component`,`icon`,`permission`,`sort`,`status`,`create_user`,`create_time`,`is_external`,`is_cache`,`is_hidden`) VALUES
(9490,'运营发布',9400,2,'/guardfind/publishing','PropertyPublishing','guardfind/publishing/index','send',NULL,9,1,1,NOW(),b'0',b'0',b'0'),
(9491,'发布查看',9490,3,NULL,NULL,NULL,NULL,'system:property:publishing:list',1,1,1,NOW(),b'0',b'0',b'0'),
(9492,'内容编辑',9490,3,NULL,NULL,NULL,NULL,'system:property:publishing:edit',2,1,1,NOW(),b'0',b'0',b'0'),
(9493,'审核与发布',9490,3,NULL,NULL,NULL,NULL,'system:property:publishing:publish',3,1,1,NOW(),b'0',b'0',b'0'),
(9494,'发布账号配置',9490,3,NULL,NULL,NULL,NULL,'system:property:publishing:accounts',4,1,1,NOW(),b'0',b'0',b'0');
INSERT IGNORE INTO sys_role_menu(role_id,menu_id,tenant_id)
SELECT DISTINCT rm.role_id,m.id,rm.tenant_id FROM sys_role_menu rm JOIN sys_menu source ON source.id=rm.menu_id
JOIN sys_menu m ON ((m.id IN(9490,9491) AND source.permission='system:property:list')
 OR (m.id=9492 AND source.permission='system:property:update')
 OR (m.id IN(9493,9494) AND source.permission='system:property:settings'));
INSERT IGNORE INTO tenant_package_menu(package_id,menu_id)
SELECT DISTINCT pm.package_id,m.id FROM tenant_package_menu pm JOIN sys_menu source ON source.id=pm.menu_id
JOIN sys_menu m ON ((m.id IN(9490,9491) AND source.permission='system:property:list')
 OR (m.id=9492 AND source.permission='system:property:update')
 OR (m.id IN(9493,9494) AND source.permission='system:property:settings'));
