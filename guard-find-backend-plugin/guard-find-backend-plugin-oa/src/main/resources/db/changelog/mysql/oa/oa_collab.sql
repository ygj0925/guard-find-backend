--liquibase formatted sql

--changeset guardfind:oa-collab
--comment: 日程与工作报告：个人/部门日程（部门日程对该部门及下级部门可见）、成员邀请与提前提醒；日报周报按员工+类型+周期唯一，提交后只读，按主管链/部门负责人/汇总权限可见

CREATE TABLE IF NOT EXISTS `oa_calendar_event` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `owner_id`             bigint(20)    NOT NULL                    COMMENT '创建人',
    `request_id`           varchar(64)   NOT NULL                    COMMENT '创建幂等请求号',
    `scope`                varchar(20)   NOT NULL                    COMMENT '范围（PERSONAL：本人与成员；DEPT：部门及下级部门）',
    `dept_id`              bigint(20)    DEFAULT NULL                COMMENT '部门日程所属部门',
    `title`                varchar(200)  NOT NULL                    COMMENT '标题',
    `location`             varchar(200)  DEFAULT NULL                COMMENT '地点',
    `description`          varchar(2000) DEFAULT NULL                COMMENT '说明',
    `start_time`           datetime      NOT NULL                    COMMENT '开始时间',
    `end_time`             datetime      NOT NULL                    COMMENT '结束时间（不含）',
    `all_day`              tinyint(1)    NOT NULL DEFAULT 0          COMMENT '是否全天',
    `remind_minutes`       int           DEFAULT NULL                COMMENT '提前提醒分钟数',
    `remind_at`            datetime      DEFAULT NULL                COMMENT '提醒时间',
    `reminded_at`          datetime      DEFAULT NULL                COMMENT '已提醒时间（改期后重置）',
    `status`               varchar(20)   NOT NULL DEFAULT 'ACTIVE'   COMMENT '状态（ACTIVE/CANCELLED）',
    `version`              int           NOT NULL DEFAULT 0          COMMENT '乐观锁版本',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_owner_request` (`tenant_id`, `owner_id`, `request_id`),
    INDEX `idx_tenant_owner_time` (`tenant_id`, `owner_id`, `start_time`),
    INDEX `idx_tenant_dept_time` (`tenant_id`, `scope`, `dept_id`, `start_time`),
    INDEX `idx_remind` (`status`, `reminded_at`, `remind_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 日程';

CREATE TABLE IF NOT EXISTS `oa_calendar_member` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `event_id`             bigint(20)    NOT NULL                    COMMENT '日程',
    `user_id`              bigint(20)    NOT NULL                    COMMENT '成员',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_event_user` (`tenant_id`, `event_id`, `user_id`),
    INDEX `idx_tenant_user` (`tenant_id`, `user_id`, `event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 日程成员';

CREATE TABLE IF NOT EXISTS `oa_work_report` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `author_id`            bigint(20)    NOT NULL                    COMMENT '填报人',
    `dept_id`              bigint(20)    DEFAULT NULL                COMMENT '填报时所属部门（部门负责人按此可见）',
    `report_type`          varchar(20)   NOT NULL                    COMMENT '类型（DAILY/WEEKLY）',
    `period_start`         date          NOT NULL                    COMMENT '周期开始（周报为周一）',
    `period_end`           date          NOT NULL                    COMMENT '周期结束（周报为周日）',
    `done_content`         text          NOT NULL                    COMMENT '已完成工作',
    `plan_content`         text          DEFAULT NULL                COMMENT '后续计划',
    `issue_content`        text          DEFAULT NULL                COMMENT '问题与协调',
    `status`               varchar(20)   NOT NULL                    COMMENT '状态（DRAFT/SUBMITTED），提交后只读',
    `submit_time`          datetime      DEFAULT NULL                COMMENT '提交时间',
    `version`              int           NOT NULL DEFAULT 0          COMMENT '乐观锁版本',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_author_period` (`tenant_id`, `author_id`, `report_type`, `period_start`),
    INDEX `idx_tenant_period` (`tenant_id`, `report_type`, `period_start`, `status`),
    INDEX `idx_tenant_dept` (`tenant_id`, `dept_id`, `status`, `period_start`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 工作报告（日报/周报）';

CREATE TABLE IF NOT EXISTS `oa_work_report_comment` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `report_id`            bigint(20)    NOT NULL                    COMMENT '报告',
    `user_id`              bigint(20)    NOT NULL                    COMMENT '评论人',
    `content`              varchar(1000) NOT NULL                    COMMENT '内容',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_tenant_report` (`tenant_id`, `report_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 工作报告批注';
