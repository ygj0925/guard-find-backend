--liquibase formatted sql

--changeset guardfind:oa-stats-1
--comment: OA 异步导出任务；文件写入非公开导出目录，仅发起人鉴权下载，过期后删除文件
CREATE TABLE IF NOT EXISTS `oa_export_task` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `user_id`              bigint(20)    NOT NULL                    COMMENT '发起人',
    `request_id`           varchar(64)   NOT NULL                    COMMENT '幂等请求号',
    `export_type`          varchar(32)   NOT NULL                    COMMENT '导出类型（APPLICATION/VEHICLE_USE）',
    `date_from`            date          NOT NULL                    COMMENT '开始日期',
    `date_to`              date          NOT NULL                    COMMENT '结束日期（含）',
    `business_type`        varchar(64)   DEFAULT NULL                COMMENT '业务类型过滤',
    `status`               varchar(20)   NOT NULL                    COMMENT '状态（PENDING/RUNNING/DONE/FAILED/EXPIRED）',
    `file_name`            varchar(200)  DEFAULT NULL                COMMENT '下载文件名',
    `file_path`            varchar(500)  DEFAULT NULL                COMMENT '导出目录内的相对路径',
    `row_count`            int           DEFAULT NULL                COMMENT '导出行数',
    `error_message`        varchar(500)  DEFAULT NULL                COMMENT '失败原因',
    `start_time`           datetime      DEFAULT NULL                COMMENT '开始生成时间',
    `finish_time`          datetime      DEFAULT NULL                COMMENT '完成时间',
    `expire_time`          datetime      DEFAULT NULL                COMMENT '文件过期时间',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_user_request` (`tenant_id`, `user_id`, `request_id`),
    INDEX `idx_tenant_user` (`tenant_id`, `user_id`, `id`),
    INDEX `idx_status_time` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 异步导出任务';
