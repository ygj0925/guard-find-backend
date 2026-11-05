--liquibase formatted sql

--changeset guardfind:oa-approval-audit
--comment: OA 审批审计（不可变；request_id 保证命令重试不产生重复轨迹）

CREATE TABLE IF NOT EXISTS `oa_approval_audit` (
    `id`                    bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`             bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `application_id`        bigint(20)    NOT NULL                    COMMENT '申请 ID',
    `process_instance_id`   varchar(64)   NOT NULL                    COMMENT 'Flowable 流程实例 ID',
    `task_id`               varchar(64)   DEFAULT NULL                COMMENT 'Flowable 任务 ID',
    `action`                varchar(32)   NOT NULL                    COMMENT '动作（SUBMIT/APPROVE/REJECT/RETURN/WITHDRAW/TRANSFER/CLAIM/TERMINATE）',
    `operator_id`           bigint(20)    NOT NULL                    COMMENT '实际操作人',
    `operator_name`         varchar(64)   DEFAULT NULL                COMMENT '操作人姓名快照',
    `original_assignee_id`  bigint(20)    DEFAULT NULL                COMMENT '原审批人（转交/代理时）',
    `reason`                varchar(500)  DEFAULT NULL                COMMENT '意见/原因',
    `request_id`            varchar(64)   NOT NULL                    COMMENT '命令幂等键',
    `detail_json`           mediumtext    DEFAULT NULL                COMMENT '决策明细快照（JSON）',
    `create_time`           datetime      NOT NULL                    COMMENT '发生时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_request` (`tenant_id`, `request_id`),
    INDEX `idx_tenant_application_time` (`tenant_id`, `application_id`, `create_time`),
    INDEX `idx_tenant_instance` (`tenant_id`, `process_instance_id`),
    INDEX `idx_tenant_task` (`tenant_id`, `task_id`),
    INDEX `idx_tenant_operator` (`tenant_id`, `operator_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 审批审计表（不可变）';
