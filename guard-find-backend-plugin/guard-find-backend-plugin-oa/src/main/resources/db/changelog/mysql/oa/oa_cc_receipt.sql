--liquibase formatted sql

--changeset guardfind:oa-cc-receipt
--comment: OA 抄送回执（每个申请每接收人唯一，读状态单向推进）

CREATE TABLE IF NOT EXISTS `oa_cc_receipt` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `application_id`       bigint(20)    NOT NULL                    COMMENT '申请 ID',
    `process_instance_id`  varchar(64)   DEFAULT NULL                COMMENT 'Flowable 流程实例 ID',
    `receiver_id`          bigint(20)    NOT NULL                    COMMENT '接收人',
    `receiver_name`        varchar(64)   DEFAULT NULL                COMMENT '接收人姓名快照',
    `source_task_id`       varchar(64)   DEFAULT NULL                COMMENT '产生抄送的流程任务 ID',
    `status`               varchar(20)   NOT NULL DEFAULT 'UNREAD'   COMMENT '状态（UNREAD：未读；READ：已读）',
    `read_time`            datetime      DEFAULT NULL                COMMENT '读取时间',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_application_receiver` (`tenant_id`, `application_id`, `receiver_id`),
    INDEX `idx_tenant_receiver_status` (`tenant_id`, `receiver_id`, `status`, `create_time`),
    INDEX `idx_tenant_application` (`tenant_id`, `application_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 抄送回执表';
