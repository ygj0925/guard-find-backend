--liquibase formatted sql

--changeset guardfind:oa-position
--comment: OA 岗位字典（租户内编码唯一，停用岗位不参与候选解析）

CREATE TABLE IF NOT EXISTS `oa_position` (
    `id`          bigint(20)  AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`   bigint(20)  NOT NULL DEFAULT 0          COMMENT '租户ID',
    `code`        varchar(64) NOT NULL                    COMMENT '岗位编码',
    `name`        varchar(64) NOT NULL                    COMMENT '岗位名称',
    `status`      varchar(20) NOT NULL DEFAULT 'ENABLED'  COMMENT '状态（ENABLED/DISABLED）',
    `sort`        int         NOT NULL DEFAULT 0          COMMENT '排序',
    `create_user` bigint(20)  NOT NULL                    COMMENT '创建人',
    `create_time` datetime    NOT NULL                    COMMENT '创建时间',
    `update_user` bigint(20)  DEFAULT NULL                COMMENT '修改人',
    `update_time` datetime    DEFAULT NULL                COMMENT '修改时间',
    `deleted`     bigint(20)  NOT NULL DEFAULT 0          COMMENT '是否已删除（0：否；id：是）',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_code` (`tenant_id`, `code`, `deleted`),
    INDEX `idx_tenant_status` (`tenant_id`, `status`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 岗位表';


--changeset guardfind:oa-employee
--comment: OA 员工档案（关联 sys_user，记录直属主管与在离职状态；部门以 sys_user.dept_id 为准）

CREATE TABLE IF NOT EXISTS `oa_employee` (
    `id`                bigint(20)  AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`         bigint(20)  NOT NULL DEFAULT 0          COMMENT '租户ID',
    `user_id`           bigint(20)  NOT NULL                    COMMENT '用户 ID（sys_user.id）',
    `employee_no`       varchar(64) DEFAULT NULL                COMMENT '工号',
    `supervisor_id`     bigint(20)  DEFAULT NULL                COMMENT '直属主管用户 ID',
    `employment_status` varchar(20) NOT NULL DEFAULT 'ACTIVE'   COMMENT '在职状态（ACTIVE/SUSPENDED/RESIGNED）',
    `dept_leader`       tinyint(1)  NOT NULL DEFAULT 0          COMMENT '是否部门负责人（0：否；1：是）',
    `hire_date`         date        DEFAULT NULL                COMMENT '入职日期',
    `leave_date`        date        DEFAULT NULL                COMMENT '离职日期',
    `create_user`       bigint(20)  NOT NULL                    COMMENT '创建人',
    `create_time`       datetime    NOT NULL                    COMMENT '创建时间',
    `update_user`       bigint(20)  DEFAULT NULL                COMMENT '修改人',
    `update_time`       datetime    DEFAULT NULL                COMMENT '修改时间',
    `deleted`           bigint(20)  NOT NULL DEFAULT 0          COMMENT '是否已删除（0：否；id：是）',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_user` (`tenant_id`, `user_id`, `deleted`),
    UNIQUE INDEX `uk_tenant_employee_no` (`tenant_id`, `employee_no`, `deleted`),
    INDEX `idx_tenant_supervisor` (`tenant_id`, `supervisor_id`),
    INDEX `idx_tenant_status` (`tenant_id`, `employment_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 员工档案表';


--changeset guardfind:oa-employee-position
--comment: OA 员工岗位关联（一个员工可任多个岗位，租户内不可重复）

CREATE TABLE IF NOT EXISTS `oa_employee_position` (
    `id`          bigint(20) AUTO_INCREMENT COMMENT 'ID',
    `tenant_id`   bigint(20) NOT NULL DEFAULT 0 COMMENT '租户ID',
    `user_id`     bigint(20) NOT NULL       COMMENT '用户 ID',
    `position_id` bigint(20) NOT NULL       COMMENT '岗位 ID',
    `create_user` bigint(20) NOT NULL       COMMENT '创建人',
    `create_time` datetime   NOT NULL       COMMENT '创建时间',
    `update_user` bigint(20) DEFAULT NULL   COMMENT '修改人',
    `update_time` datetime   DEFAULT NULL   COMMENT '修改时间',
    `deleted`     bigint(20) NOT NULL DEFAULT 0 COMMENT '是否已删除（0：否；id：是）',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_user_position` (`tenant_id`, `user_id`, `position_id`, `deleted`),
    INDEX `idx_tenant_position` (`tenant_id`, `position_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 员工岗位关联表';
