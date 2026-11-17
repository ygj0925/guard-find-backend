-- liquibase formatted sql
-- changeset guardfind:property-operations-workflow-1 dbms:mysql
CREATE TABLE gf_property_operation (
 property_id bigint PRIMARY KEY, manager_id bigint NULL, availability varchar(20) NOT NULL DEFAULT 'ACTIVE',
 reason varchar(500) NULL, reopen_date date NULL, latitude decimal(10,7) NULL, longitude decimal(10,7) NULL,
 meeting_point varchar(200) NULL, instructions varchar(2000) NULL, version bigint NOT NULL DEFAULT 0,
 update_time datetime NOT NULL
);
CREATE TABLE gf_property_booking_log (
 id bigint PRIMARY KEY, booking_id bigint NOT NULL, actor_type varchar(20) NOT NULL, actor_id bigint NULL,
 action varchar(30) NOT NULL, from_status int NULL, to_status int NULL, reason varchar(500) NULL,
 create_time datetime NOT NULL, INDEX idx_booking_log(booking_id,create_time,id)
);
CREATE TABLE gf_property_support_ticket (
 user_id bigint PRIMARY KEY, owner_admin_id bigint NULL, state varchar(20) NOT NULL DEFAULT 'OPEN',
 property_id bigint NULL, version bigint NOT NULL DEFAULT 0, update_time datetime NOT NULL
);
CREATE TABLE gf_property_support_log (
 id bigint PRIMARY KEY, user_id bigint NOT NULL, admin_id bigint NULL, action varchar(20) NOT NULL,
 note varchar(2000) NULL, create_time datetime NOT NULL, INDEX idx_support_log(user_id,create_time,id)
);
CREATE TABLE gf_property_ops_event (
 id bigint PRIMARY KEY, property_id bigint NULL, user_id bigint NULL, event_type varchar(20) NOT NULL,
 create_time datetime NOT NULL, INDEX idx_ops_event(create_time,event_type,property_id)
);
CREATE TABLE gf_property_ops_config (id bigint PRIMARY KEY, content_json longtext NOT NULL);
INSERT INTO gf_property_ops_config VALUES (1,'{}');
CREATE TABLE gf_property_subscription (
 user_id bigint NOT NULL, template_id varchar(100) NOT NULL, credits int NOT NULL DEFAULT 0,
 update_time datetime NOT NULL, PRIMARY KEY(user_id,template_id)
);
CREATE TABLE gf_property_notification (
 id bigint PRIMARY KEY, booking_id bigint NOT NULL, user_id bigint NOT NULL, event_key varchar(100) NOT NULL,
 template_id varchar(100) NOT NULL, payload longtext NOT NULL, state varchar(30) NOT NULL,
 result varchar(500) NULL, create_time datetime NOT NULL, update_time datetime NOT NULL,
 UNIQUE KEY uk_notification_event(event_key,user_id), INDEX idx_notification_state(state,create_time)
);
CREATE TABLE gf_property_policy_accept (
 user_id bigint NOT NULL, policy_version varchar(40) NOT NULL, create_time datetime NOT NULL,
 PRIMARY KEY(user_id,policy_version)
);

-- changeset guardfind:property-operations-workflow-menu-1 dbms:mysql
INSERT INTO sys_menu (`id`,`title`,`parent_id`,`type`,`path`,`name`,`component`,`icon`,`permission`,`sort`,`status`,`create_user`,`create_time`,`is_external`,`is_cache`,`is_hidden`) VALUES
(9460,'运营看板',9400,2,'/guardfind/operations','PropertyOperations','guardfind/operations/index','dashboard',NULL,0,1,1,NOW(),b'0',b'0',b'0'),
(9461,'小程序用户',9400,2,'/guardfind/customers','PropertyCustomers','guardfind/customers/index','user',NULL,5,1,1,NOW(),b'0',b'0',b'0'),
(9462,'运营配置',9400,2,'/guardfind/settings','PropertySettings','guardfind/settings/index','setting',NULL,6,1,1,NOW(),b'0',b'0',b'0'),
(9463,'客服接待',9400,2,'/guardfind/workflow','PropertyWorkflow','guardfind/workflow/index','team',NULL,7,1,1,NOW(),b'0',b'0',b'0'),
(9464,'运营统计',9460,3,NULL,NULL,NULL,NULL,'system:property:metrics',1,1,1,NOW(),b'0',b'0',b'0'),
(9465,'用户列表',9461,3,NULL,NULL,NULL,NULL,'system:property:customer:list',1,1,1,NOW(),b'0',b'0',b'0'),
(9466,'用户状态',9461,3,NULL,NULL,NULL,NULL,'system:property:customer:update',2,1,1,NOW(),b'0',b'0',b'0'),
(9467,'运营配置管理',9462,3,NULL,NULL,NULL,NULL,'system:property:settings',1,1,1,NOW(),b'0',b'0',b'0'),
(9468,'分配服务管家',9410,3,NULL,NULL,NULL,NULL,'system:property:assign',9,1,1,NOW(),b'0',b'0',b'0'),
(9469,'会话接待处理',9463,3,NULL,NULL,NULL,NULL,'system:im:workflow',1,1,1,NOW(),b'0',b'0',b'0'),
(9470,'会话接待主管',9463,3,NULL,NULL,NULL,NULL,'system:im:workflow:manage',2,1,1,NOW(),b'0',b'0',b'0');
INSERT IGNORE INTO sys_role_menu(role_id,menu_id,tenant_id)
SELECT DISTINCT rm.role_id,m.id,rm.tenant_id FROM sys_role_menu rm JOIN sys_menu source ON source.id=rm.menu_id
JOIN sys_menu m ON ((m.id IN(9460,9464) AND source.permission='system:property:list')
 OR (m.id IN(9461,9465,9466) AND source.permission='system:property:manager:set')
 OR (m.id IN(9462,9467,9468) AND source.permission='system:property:update')
 OR (m.id IN(9463,9469) AND source.permission='system:im:send'));
INSERT IGNORE INTO tenant_package_menu(package_id,menu_id)
SELECT DISTINCT pm.package_id,m.id FROM tenant_package_menu pm JOIN sys_menu source ON source.id=pm.menu_id
JOIN sys_menu m ON ((m.id IN(9460,9464) AND source.permission='system:property:list')
 OR (m.id IN(9461,9465,9466) AND source.permission='system:property:manager:set')
 OR (m.id IN(9462,9467,9468) AND source.permission='system:property:update')
 OR (m.id IN(9463,9469) AND source.permission='system:im:send'));
