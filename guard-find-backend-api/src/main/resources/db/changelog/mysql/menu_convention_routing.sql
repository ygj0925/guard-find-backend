-- liquibase formatted sql

-- changeset yuanguangji:1
-- comment 系统日志由单页改为目录，新增登录/操作/访问日志三个子页（配合前端约定式路由迁移）
-- preconditions onFail:MARK_RAN
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys_menu WHERE id IN (2034, 2035, 2036)
UPDATE sys_menu SET type = 1, component = 'Layout', redirect = '/monitor/log/login'
WHERE id = 2030 AND deleted = 0;

INSERT INTO sys_menu (id, title, parent_id, type, path, name, component, redirect, icon, is_external, is_cache, is_hidden, permission, sort, status, create_user, create_time) VALUES
(2034, '登录日志', 2030, 2, '/monitor/log/login', 'MonitorLoginLog', 'monitor/log/login/index', NULL, NULL, b'0', b'0', b'0', NULL, 1, 1, 1, NOW()),
(2035, '操作日志', 2030, 2, '/monitor/log/operation', 'MonitorOperationLog', 'monitor/log/operation/index', NULL, NULL, b'0', b'0', b'0', NULL, 2, 1, 1, NOW()),
(2036, '访问日志', 2030, 2, '/monitor/log/access', 'MonitorAccessLog', 'monitor/log/access/index', NULL, NULL, b'0', b'0', b'0', NULL, 3, 1, 1, NOW());

-- changeset yuanguangji:2
-- comment 新增仪表盘目录及工作台/数据分析/驾驶舱三个子页，登录后默认落地工作台
-- preconditions onFail:MARK_RAN
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys_menu WHERE id IN (9100, 9110, 9120, 9130)
INSERT INTO sys_menu (id, title, parent_id, type, path, name, component, redirect, icon, is_external, is_cache, is_hidden, permission, sort, status, create_user, create_time) VALUES
(9100, '仪表盘', 0, 1, '/dashboard', 'Dashboard', 'Layout', '/dashboard/workplace', 'dashboard', b'0', b'0', b'0', NULL, 0, 1, 1, NOW()),
(9110, '工作台', 9100, 2, '/dashboard/workplace', 'DashboardWorkplace', 'dashboard/workplace/index', NULL, NULL, b'0', b'0', b'0', NULL, 1, 1, 1, NOW()),
(9120, '数据分析', 9100, 2, '/dashboard/analysis', 'DashboardAnalysis', 'dashboard/analysis/index', NULL, NULL, b'0', b'0', b'0', NULL, 2, 1, 1, NOW()),
(9130, '驾驶舱', 9100, 2, '/dashboard/cockpit', 'DashboardCockpit', 'dashboard/cockpit/index', NULL, NULL, b'0', b'0', b'0', NULL, 3, 1, 1, NOW());

-- changeset yuanguangji:3
-- comment 新增智能助手全屏页菜单（悬浮窗之外的侧边栏入口）
-- preconditions onFail:MARK_RAN
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys_menu WHERE id = 9200
INSERT INTO sys_menu (id, title, parent_id, type, path, name, component, redirect, icon, is_external, is_cache, is_hidden, permission, sort, status, create_user, create_time) VALUES
(9200, '智能助手', 0, 2, '/chat', 'Chat', 'chat/index', NULL, 'smile', b'0', b'0', b'0', NULL, 10, 1, 1, NOW());

-- changeset yuanguangji:4
-- comment 系统管理目录补齐无菜单页面：国际化管理/短信配置/存储管理
-- preconditions onFail:MARK_RAN
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys_menu WHERE id IN (1260, 1270, 1280)
INSERT INTO sys_menu (id, title, parent_id, type, path, name, component, redirect, icon, is_external, is_cache, is_hidden, permission, sort, status, create_user, create_time) VALUES
(1260, '国际化管理', 1000, 2, '/i18n', 'SystemI18n', 'i18n/index', NULL, NULL, b'0', b'0', b'0', NULL, 8, 1, 1, NOW()),
(1270, '短信配置', 1000, 2, '/system/sms-config', 'SystemSmsConfig', 'system/sms-config/index', NULL, NULL, b'0', b'0', b'0', NULL, 9, 1, 1, NOW()),
(1280, '存储管理', 1000, 2, '/system/storage', 'SystemStorage', 'system/storage/index', NULL, NULL, b'0', b'0', b'0', NULL, 10, 1, 1, NOW());

-- changeset yuanguangji:5
-- comment 新增隐藏菜单：个人中心（头像下拉入口）、消息中心（铃铛入口）
-- preconditions onFail:MARK_RAN
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM sys_menu WHERE id IN (9300, 9310)
INSERT INTO sys_menu (id, title, parent_id, type, path, name, component, redirect, icon, is_external, is_cache, is_hidden, permission, sort, status, create_user, create_time) VALUES
(9300, '个人中心', 0, 2, '/user/profile', 'UserProfile', 'user/profile/index', NULL, NULL, b'0', b'0', b'1', NULL, 90, 1, 1, NOW()),
(9310, '消息中心', 0, 2, '/user/message', 'UserMessage', 'user/message/index', NULL, NULL, b'0', b'0', b'1', NULL, 91, 1, 1, NOW());
