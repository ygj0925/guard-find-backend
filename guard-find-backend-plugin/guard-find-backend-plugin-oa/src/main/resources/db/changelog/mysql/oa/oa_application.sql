--liquibase formatted sql

--changeset guardfind:oa-application
--comment: OA 申请主表（绑定发布版本与表单版本，业务数据在各自业务表）

CREATE TABLE IF NOT EXISTS `oa_application` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `business_type`        varchar(64)   NOT NULL                    COMMENT '业务类型（如 LEAVE/EXPENSE）',
    `business_key`         varchar(128)  NOT NULL                    COMMENT '业务单号/业务主键',
    `title`                varchar(200)  NOT NULL                    COMMENT '申请标题',
    `applicant_id`         bigint(20)    NOT NULL                    COMMENT '发起人',
    `applicant_name`       varchar(64)   DEFAULT NULL                COMMENT '发起人姓名快照',
    `applicant_dept_id`    bigint(20)    DEFAULT NULL                COMMENT '发起部门 ID 快照',
    `applicant_dept_name`  varchar(100)  DEFAULT NULL                COMMENT '发起部门名称快照',
    `release_id`           bigint(20)    NOT NULL                    COMMENT '流程发布版本 ID',
    `form_version_id`      bigint(20)    DEFAULT NULL                COMMENT '表单版本快照 ID',
    `form_version`         int           DEFAULT NULL                COMMENT '表单版本号',
    `form_data`            mediumtext    DEFAULT NULL                COMMENT '提交时的表单数据快照（JSON）',
    `process_instance_id`  varchar(64)   DEFAULT NULL                COMMENT 'Flowable 流程实例 ID',
    `status`               varchar(20)   NOT NULL DEFAULT 'DRAFT'    COMMENT '状态（DRAFT/RUNNING/APPROVED/REJECTED/WITHDRAWN/CANCELLED）',
    `submitted_time`       datetime      DEFAULT NULL                COMMENT '提交时间',
    `finished_time`        datetime      DEFAULT NULL                COMMENT '结束时间',
    `version`              int           NOT NULL DEFAULT 0          COMMENT '乐观锁版本',
    `create_user`          bigint(20)    NOT NULL                    COMMENT '创建人',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_user`          bigint(20)    DEFAULT NULL                COMMENT '修改人',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    `deleted`              bigint(20)    NOT NULL DEFAULT 0          COMMENT '是否已删除（0：否；id：是）',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_business` (`tenant_id`, `business_type`, `business_key`, `deleted`),
    UNIQUE INDEX `uk_process_instance` (`process_instance_id`),
    INDEX `idx_tenant_applicant` (`tenant_id`, `applicant_id`, `status`),
    INDEX `idx_tenant_status_time` (`tenant_id`, `status`, `create_time`),
    INDEX `idx_tenant_dept` (`tenant_id`, `applicant_dept_id`),
    INDEX `idx_tenant_release` (`tenant_id`, `release_id`),
    INDEX `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 申请主表';
