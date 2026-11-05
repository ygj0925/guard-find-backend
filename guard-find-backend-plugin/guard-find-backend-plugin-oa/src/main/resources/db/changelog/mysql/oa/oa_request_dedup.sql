--liquibase formatted sql

--changeset guardfind:oa-request-dedup
--comment: OA 命令幂等记录（租户+操作人+命令+request_id 唯一，保留结果用于重试回放）

CREATE TABLE IF NOT EXISTS `oa_request_dedup` (
    `id`            bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`     bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `actor_id`      bigint(20)    NOT NULL                    COMMENT '操作人',
    `command`       varchar(64)   NOT NULL                    COMMENT '命令（如 SUBMIT/APPROVE）',
    `request_id`    varchar(64)   NOT NULL                    COMMENT '客户端请求 ID',
    `status`        varchar(20)   NOT NULL DEFAULT 'PROCESSING' COMMENT '状态（PROCESSING/SUCCEEDED/FAILED）',
    `result_json`   mediumtext    DEFAULT NULL                COMMENT '成功结果快照（JSON）',
    `create_time`   datetime      NOT NULL                    COMMENT '创建时间',
    `expire_time`   datetime      NOT NULL                    COMMENT '过期时间（清理作业依据）',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_actor_command_request` (`tenant_id`, `actor_id`, `command`, `request_id`),
    INDEX `idx_expire_time` (`expire_time`),
    INDEX `idx_tenant_created` (`tenant_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 命令幂等表';
