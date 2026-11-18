-- liquibase formatted sql
-- changeset guardfind:property-identity-verification-1 dbms:mysql
CREATE TABLE gf_property_identity_config (
 id bigint PRIMARY KEY, mode varchar(20) NOT NULL DEFAULT 'DISABLED', provider varchar(50) NULL,
 required_for_signing bit NOT NULL DEFAULT b'0', verification_ttl_days int NOT NULL DEFAULT 365,
 consent_version varchar(40) NOT NULL DEFAULT 'identity-v1',
 update_time datetime NOT NULL
);
INSERT INTO gf_property_identity_config(id,mode,provider,required_for_signing,verification_ttl_days,consent_version,update_time)
VALUES(1,'DISABLED',NULL,b'0',365,'identity-v1',NOW());

CREATE TABLE gf_property_identity_verification (
 id bigint PRIMARY KEY, user_id bigint NOT NULL, status varchar(20) NOT NULL, version bigint NOT NULL DEFAULT 0,
 real_name varchar(100) NULL, id_number_masked varchar(32) NULL, id_number_hash varchar(64) NULL,
 provider varchar(50) NULL, provider_session_id varchar(128) NULL, provider_launch_url varchar(1000) NULL, provider_reference varchar(128) NULL,
 consent_version varchar(40) NOT NULL, consent_time datetime(6) NOT NULL,
 failure_reason varchar(500) NULL, verified_at datetime(6) NULL, expires_at datetime(6) NULL,
 reviewer_id bigint NULL, review_time datetime(6) NULL,
 create_time datetime(6) NOT NULL, update_time datetime(6) NOT NULL,
 INDEX idx_identity_user(user_id,status,update_time), UNIQUE KEY uk_identity_provider_session(provider,provider_session_id)
);

-- changeset guardfind:property-identity-verification-menu-1 dbms:mysql
INSERT IGNORE INTO sys_menu (`id`,`title`,`parent_id`,`type`,`path`,`name`,`component`,`icon`,`permission`,`sort`,`status`,`create_user`,`create_time`,`is_external`,`is_cache`,`is_hidden`) VALUES
(9495,'身份核验',9400,2,'/guardfind/identity-verification','PropertyIdentityVerification','guardfind/identity-verification/index','user',NULL,10,1,1,NOW(),b'0',b'0',b'0'),
(9496,'核验查看',9495,3,NULL,NULL,NULL,NULL,'system:property:identity:list',1,1,1,NOW(),b'0',b'0',b'0'),
(9497,'核验处理',9495,3,NULL,NULL,NULL,NULL,'system:property:identity:update',2,1,1,NOW(),b'0',b'0',b'0');
INSERT IGNORE INTO sys_role_menu(role_id,menu_id,tenant_id)
SELECT DISTINCT rm.role_id,m.id,rm.tenant_id FROM sys_role_menu rm JOIN sys_menu source ON source.id=rm.menu_id
JOIN sys_menu m ON ((m.id IN(9495,9496) AND source.permission='system:property:list')
 OR (m.id=9497 AND source.permission='system:property:update'));
INSERT IGNORE INTO tenant_package_menu(package_id,menu_id)
SELECT DISTINCT pm.package_id,m.id FROM tenant_package_menu pm JOIN sys_menu source ON source.id=pm.menu_id
JOIN sys_menu m ON ((m.id IN(9495,9496) AND source.permission='system:property:list')
 OR (m.id=9497 AND source.permission='system:property:update'));
