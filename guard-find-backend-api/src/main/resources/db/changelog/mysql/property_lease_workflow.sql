-- liquibase formatted sql
-- changeset guardfind:property-lease-workflow-1 dbms:mysql
CREATE TABLE gf_property_lease (
 id bigint PRIMARY KEY, property_id bigint NOT NULL, property_title varchar(200) NOT NULL, property_address varchar(500) NOT NULL,
 tenant_id bigint NOT NULL, landlord_id bigint NOT NULL, title varchar(200) NOT NULL,
 landlord_name varchar(100) NOT NULL, tenant_name varchar(100) NOT NULL,
 start_date date NOT NULL, end_date date NOT NULL, monthly_rent decimal(12,2) NOT NULL, deposit decimal(12,2) NOT NULL,
 terms longtext NOT NULL, status varchar(30) NOT NULL DEFAULT 'DRAFT', version bigint NOT NULL DEFAULT 0,
 content_hash varchar(64) NULL, published_at datetime NULL, tenant_signed_at datetime NULL, landlord_signed_at datetime NULL,
 tenant_signed_name varchar(100) NULL, landlord_signed_name varchar(100) NULL,
 previous_lease_id bigint NULL, renewal_lease_id bigint NULL, renewal_status varchar(20) NOT NULL DEFAULT 'NONE',
 requested_end_date date NULL, renewal_reason varchar(1000) NULL,
 move_out_status varchar(20) NOT NULL DEFAULT 'NONE', move_out_reason varchar(1000) NULL,
 create_time datetime NOT NULL, update_time datetime NOT NULL,
 INDEX idx_lease_property(property_id,status,start_date,end_date), INDEX idx_lease_tenant(tenant_id,id),
 INDEX idx_lease_landlord(landlord_id,id), INDEX idx_lease_expiry(status,end_date)
);
CREATE TABLE gf_property_lease_log (
 id bigint PRIMARY KEY, lease_id bigint NOT NULL, actor_type varchar(20) NOT NULL, actor_id bigint NULL,
 action varchar(30) NOT NULL, reason varchar(1000) NULL, content_hash varchar(64) NULL, create_time datetime NOT NULL,
 INDEX idx_lease_log(lease_id,id)
);
CREATE TABLE gf_property_lease_reminder (
 id bigint PRIMARY KEY, lease_id bigint NOT NULL, threshold_days int NOT NULL, end_date date NOT NULL,
 state varchar(20) NOT NULL, create_time datetime NOT NULL, UNIQUE KEY uk_lease_reminder(lease_id,threshold_days,end_date)
);
CREATE TABLE gf_property_lease_config (id bigint PRIMARY KEY, content_json longtext NOT NULL);
INSERT INTO gf_property_lease_config VALUES(1,'{}');

-- changeset guardfind:property-lease-menu-1 dbms:mysql
INSERT INTO sys_menu (`id`,`title`,`parent_id`,`type`,`path`,`name`,`component`,`icon`,`permission`,`sort`,`status`,`create_user`,`create_time`,`is_external`,`is_cache`,`is_hidden`) VALUES
(9480,'合同与续租',9400,2,'/guardfind/leases','PropertyLeases','guardfind/leases/index','file',NULL,8,1,1,NOW(),b'0',b'0',b'0'),
(9481,'合同查看',9480,3,NULL,NULL,NULL,NULL,'system:property:lease:list',1,1,1,NOW(),b'0',b'0',b'0'),
(9482,'合同创建',9480,3,NULL,NULL,NULL,NULL,'system:property:lease:create',2,1,1,NOW(),b'0',b'0',b'0'),
(9483,'合同编辑',9480,3,NULL,NULL,NULL,NULL,'system:property:lease:update',3,1,1,NOW(),b'0',b'0',b'0'),
(9484,'合同发布',9480,3,NULL,NULL,NULL,NULL,'system:property:lease:publish',4,1,1,NOW(),b'0',b'0',b'0'),
(9485,'合同终止',9480,3,NULL,NULL,NULL,NULL,'system:property:lease:terminate',5,1,1,NOW(),b'0',b'0',b'0');
INSERT IGNORE INTO sys_role_menu(role_id,menu_id,tenant_id)
SELECT DISTINCT rm.role_id,m.id,rm.tenant_id FROM sys_role_menu rm JOIN sys_menu source ON source.id=rm.menu_id
JOIN sys_menu m ON ((m.id IN(9480,9481) AND source.permission='system:property:list')
 OR (m.id IN(9482,9483) AND source.permission='system:property:update')
 OR (m.id IN(9484,9485) AND source.permission='system:property:status'));
INSERT IGNORE INTO tenant_package_menu(package_id,menu_id)
SELECT DISTINCT pm.package_id,m.id FROM tenant_package_menu pm JOIN sys_menu source ON source.id=pm.menu_id
JOIN sys_menu m ON ((m.id IN(9480,9481) AND source.permission='system:property:list')
 OR (m.id IN(9482,9483) AND source.permission='system:property:update')
 OR (m.id IN(9484,9485) AND source.permission='system:property:status'));
