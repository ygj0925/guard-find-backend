--liquibase formatted sql

--changeset guardfind:oa-form
--comment: OA 表单定义与不可变版本快照（版本冻结后不可修改）

CREATE TABLE IF NOT EXISTS `oa_form` (
    `id`                 bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`          bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `form_key`           varchar(64)   NOT NULL                    COMMENT '表单稳定标识（租户内唯一）',
    `name`               varchar(100)  NOT NULL                    COMMENT '表单名称',
    `description`        varchar(500)  DEFAULT NULL                COMMENT '说明',
    `status`             varchar(20)   NOT NULL DEFAULT 'DRAFT'    COMMENT '状态（DRAFT：草稿；PUBLISHED：有生效版本；DISABLED：停用）',
    `current_version`    int           NOT NULL DEFAULT 0          COMMENT '当前生效版本号',
    `current_version_id` bigint(20)    DEFAULT NULL                COMMENT '当前生效版本 ID',
    `version`            int           NOT NULL DEFAULT 0          COMMENT '乐观锁版本',
    `create_user`        bigint(20)    NOT NULL                    COMMENT '创建人',
    `create_time`        datetime      NOT NULL                    COMMENT '创建时间',
    `update_user`        bigint(20)    DEFAULT NULL                COMMENT '修改人',
    `update_time`        datetime      DEFAULT NULL                COMMENT '修改时间',
    `deleted`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '是否已删除（0：否；id：是）',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_form_key` (`tenant_id`, `form_key`, `deleted`),
    INDEX `idx_tenant_status` (`tenant_id`, `status`),
    INDEX `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 表单定义表';

CREATE TABLE IF NOT EXISTS `oa_form_version` (
    `id`             bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`      bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `form_id`        bigint(20)    NOT NULL                    COMMENT '表单 ID',
    `version`        int           NOT NULL                    COMMENT '版本号（表单内单调递增）',
    `schema_json`    mediumtext    NOT NULL                    COMMENT '表单快照（字段/布局/校验/可见可写）',
    `schema_digest`  varchar(64)   NOT NULL                    COMMENT '快照 SHA-256',
    `field_count`    int           NOT NULL DEFAULT 0          COMMENT '字段数量',
    `published_by`   bigint(20)    NOT NULL                    COMMENT '发布人',
    `publish_time`   datetime      NOT NULL                    COMMENT '发布时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_form_version` (`tenant_id`, `form_id`, `version`),
    INDEX `idx_tenant_form` (`tenant_id`, `form_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 表单版本快照表（不可变）';
