--liquibase formatted sql

--changeset guardfind:oa-delegate-rule
--comment: OA 限时代理规则（同一委托人/代理人有效期内唯一；软删除用 deleted=id 释放唯一键）

CREATE TABLE IF NOT EXISTS `oa_delegate_rule` (
    `id`          bigint(20)   AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`   bigint(20)   NOT NULL DEFAULT 0          COMMENT '租户ID',
    `delegator_id` bigint(20)  NOT NULL                    COMMENT '委托人用户 ID',
    `delegate_id`  bigint(20)  NOT NULL                    COMMENT '代理人用户 ID',
    `start_time`   datetime    NOT NULL                    COMMENT '代理开始时间',
    `end_time`     datetime    NOT NULL                    COMMENT '代理结束时间',
    `status`       varchar(20) NOT NULL DEFAULT 'ENABLED'  COMMENT '状态（ENABLED/DISABLED）',
    `reason`       varchar(500) DEFAULT NULL               COMMENT '代理原因',
    `create_user`  bigint(20)  NOT NULL                    COMMENT '创建人',
    `create_time`  datetime    NOT NULL                    COMMENT '创建时间',
    `update_user`  bigint(20)  DEFAULT NULL                COMMENT '修改人',
    `update_time`  datetime    DEFAULT NULL                COMMENT '修改时间',
    `deleted`      bigint(20)  NOT NULL DEFAULT 0          COMMENT '是否已删除（0：否；id：是）',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_delegator_delegate` (`tenant_id`, `delegator_id`, `delegate_id`, `deleted`),
    INDEX `idx_tenant_delegate_window` (`tenant_id`, `delegate_id`, `status`, `start_time`, `end_time`),
    INDEX `idx_tenant_delegator` (`tenant_id`, `delegator_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 限时代理规则表';
