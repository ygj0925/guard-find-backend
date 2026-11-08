--liquibase formatted sql

--changeset guardfind:oa-outbox-dedup
--comment: outbox 增加业务去重键，同一租户同一事件只投递一次

ALTER TABLE `oa_outbox` ADD COLUMN `dedup_key` varchar(160) DEFAULT NULL COMMENT '业务去重键' AFTER `payload`;
ALTER TABLE `oa_outbox` ADD UNIQUE INDEX `uk_tenant_dedup` (`tenant_id`, `dedup_key`);


--changeset guardfind:oa-notification
--comment: OA 站内通知（由 outbox 投递，接收人各自唯一）

CREATE TABLE IF NOT EXISTS `oa_notification` (
    `id`              bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`       bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `user_id`         bigint(20)    NOT NULL                    COMMENT '接收人',
    `type`            varchar(32)   NOT NULL                    COMMENT '类型（TODO/URGED/OVERDUE/APPROVED/REJECTED/RETURNED/CANCELLED/TRANSFERRED）',
    `title`           varchar(200)  NOT NULL                    COMMENT '标题',
    `content`         varchar(1000) DEFAULT NULL                COMMENT '内容',
    `application_id`  bigint(20)    DEFAULT NULL                COMMENT '申请 ID',
    `task_id`         varchar(64)   DEFAULT NULL                COMMENT '任务 ID',
    `dedup_key`       varchar(160)  NOT NULL                    COMMENT '事件去重键',
    `status`          varchar(20)   NOT NULL DEFAULT 'UNREAD'   COMMENT '状态（UNREAD：未读；READ：已读）',
    `read_time`       datetime      DEFAULT NULL                COMMENT '读取时间',
    `create_time`     datetime      NOT NULL                    COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_user_dedup` (`tenant_id`, `user_id`, `dedup_key`),
    INDEX `idx_tenant_user_status` (`tenant_id`, `user_id`, `status`, `create_time`),
    INDEX `idx_tenant_application` (`tenant_id`, `application_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 站内通知表';
