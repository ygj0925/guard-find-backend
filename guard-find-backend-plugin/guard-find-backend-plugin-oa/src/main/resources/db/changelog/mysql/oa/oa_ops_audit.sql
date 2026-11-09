--liquibase formatted sql

--changeset guardfind:oa-ops-audit-1
--comment: OA 运维干预审计；终止、outbox 重试与立即投递均追加写入，不提供修改与删除
CREATE TABLE IF NOT EXISTS `oa_ops_audit` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `action`               varchar(32)   NOT NULL                    COMMENT '动作（TERMINATE/OUTBOX_RETRY/OUTBOX_DISPATCH）',
    `target_type`          varchar(32)   NOT NULL                    COMMENT '对象类型（APPLICATION/OUTBOX/OUTBOX_BATCH）',
    `target_id`            varchar(64)   DEFAULT NULL                COMMENT '对象ID',
    `operator_id`          bigint(20)    NOT NULL                    COMMENT '操作人',
    `operator_name`        varchar(64)   DEFAULT NULL                COMMENT '操作人名称快照',
    `reason`               varchar(500)  DEFAULT NULL                COMMENT '原因',
    `detail_json`          text          DEFAULT NULL                COMMENT '动作明细',
    `create_time`          datetime      NOT NULL                    COMMENT '操作时间',
    PRIMARY KEY (`id`),
    INDEX `idx_tenant_time` (`tenant_id`, `create_time`),
    INDEX `idx_tenant_action_time` (`tenant_id`, `action`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 运维干预审计';
