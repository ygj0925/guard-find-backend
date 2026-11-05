--liquibase formatted sql

--changeset guardfind:oa-outbox
--comment: OA 事务内 outbox（事件 ID 唯一，提交后由后台作业重试投递）

CREATE TABLE IF NOT EXISTS `oa_outbox` (
    `id`               bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`        bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `event_id`         varchar(64)   NOT NULL                    COMMENT '事件唯一 ID（UUID）',
    `aggregate_type`   varchar(64)   NOT NULL                    COMMENT '聚合类型（如 APPLICATION）',
    `aggregate_id`     varchar(128)  NOT NULL                    COMMENT '聚合 ID',
    `event_type`       varchar(64)   NOT NULL                    COMMENT '事件类型（如 APPLICATION_SUBMITTED）',
    `payload`          mediumtext    NOT NULL                    COMMENT '事件负载（JSON）',
    `status`           varchar(20)   NOT NULL DEFAULT 'PENDING'  COMMENT '状态（PENDING/PROCESSING/SENT/FAILED）',
    `retry_count`      int           NOT NULL DEFAULT 0          COMMENT '已重试次数',
    `next_retry_time`  datetime      DEFAULT NULL                COMMENT '下次可投递时间',
    `last_error`       varchar(1000) DEFAULT NULL                COMMENT '最近一次失败原因',
    `create_time`      datetime      NOT NULL                    COMMENT '创建时间',
    `update_time`      datetime      DEFAULT NULL                COMMENT '最近处理时间',
    `sent_time`        datetime      DEFAULT NULL                COMMENT '投递成功时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_event_id` (`event_id`),
    INDEX `idx_status_retry` (`status`, `next_retry_time`),
    INDEX `idx_tenant_created` (`tenant_id`, `create_time`),
    INDEX `idx_tenant_aggregate` (`tenant_id`, `aggregate_type`, `aggregate_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 事件 outbox 表';
