--liquibase formatted sql

--changeset guardfind:oa-process-model
--comment: OA 流程模型与不可变发布版本（Flowable 7.2.0；表由 Liquibase 托管）

CREATE TABLE IF NOT EXISTS `oa_process_model` (
    `id`                    bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`             bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `model_key`             varchar(64)   NOT NULL                    COMMENT '模型稳定标识（租户内唯一）',
    `name`                  varchar(100)  NOT NULL                    COMMENT '模型名称',
    `category`              varchar(64)   DEFAULT NULL                COMMENT '分类',
    `description`           varchar(500)  DEFAULT NULL                COMMENT '说明',
    `bpmn_xml`              mediumtext    DEFAULT NULL                COMMENT 'BPMN XML 草稿',
    `xml_digest`            varchar(64)   DEFAULT NULL                COMMENT 'BPMN XML SHA-256',
    `status`                varchar(20)   NOT NULL DEFAULT 'DRAFT'    COMMENT '状态（DRAFT：草稿；PUBLISHED：有生效版本；DISABLED：停用）',
    `current_release_id`    bigint(20)    DEFAULT NULL                COMMENT '当前生效发布版本 ID',
    `current_release_version` int         NOT NULL DEFAULT 0          COMMENT '当前生效发布版本号',
    `form_id`               bigint(20)    DEFAULT NULL                COMMENT '绑定表单 ID',
    `form_version_id`       bigint(20)    DEFAULT NULL                COMMENT '绑定的表单版本 ID',
    `version`               int           NOT NULL DEFAULT 0          COMMENT '乐观锁版本',
    `create_user`           bigint(20)    NOT NULL                    COMMENT '创建人',
    `create_time`           datetime      NOT NULL                    COMMENT '创建时间',
    `update_user`           bigint(20)    DEFAULT NULL                COMMENT '修改人',
    `update_time`           datetime      DEFAULT NULL                COMMENT '修改时间',
    `deleted`               bigint(20)    NOT NULL DEFAULT 0          COMMENT '是否已删除（0：否；id：是）',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_model_key` (`tenant_id`, `model_key`, `deleted`),
    INDEX `idx_tenant_status` (`tenant_id`, `status`),
    INDEX `idx_tenant_form` (`tenant_id`, `form_id`),
    INDEX `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 流程模型表';

CREATE TABLE IF NOT EXISTS `oa_process_release` (
    `id`                      bigint(20)    AUTO_INCREMENT            COMMENT 'ID',
    `tenant_id`               bigint(20)    NOT NULL DEFAULT 0        COMMENT '租户ID',
    `model_id`                bigint(20)    NOT NULL                  COMMENT '模型 ID',
    `release_version`         int           NOT NULL                  COMMENT '发布版本号（模型内单调递增）',
    `bpmn_xml`                mediumtext    NOT NULL                  COMMENT '发布时冻结的 BPMN XML',
    `xml_digest`              varchar(64)   NOT NULL                  COMMENT '发布 XML SHA-256',
    `engine_definition_id`    varchar(128)  NOT NULL                  COMMENT 'Flowable 流程定义 ID',
    `process_definition_key`  varchar(128)  NOT NULL                  COMMENT '流程定义 Key',
    `process_definition_version` int        NOT NULL                  COMMENT 'Flowable 流程定义版本',
    `form_id`                 bigint(20)    DEFAULT NULL              COMMENT '绑定表单 ID',
    `form_version_id`         bigint(20)    DEFAULT NULL              COMMENT '绑定表单版本 ID',
    `form_version`            int           DEFAULT NULL              COMMENT '绑定表单版本号',
    `launch_scope`            varchar(2000) DEFAULT NULL              COMMENT '可发起范围（JSON）',
    `status`                  varchar(20)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态（ACTIVE：生效；STOPPED：停用）',
    `published_by`            bigint(20)    NOT NULL                  COMMENT '发布人',
    `publish_time`            datetime      NOT NULL                  COMMENT '发布时间',
    `remark`                  varchar(500)  DEFAULT NULL              COMMENT '发布说明',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_model_version` (`tenant_id`, `model_id`, `release_version`),
    UNIQUE INDEX `uk_engine_definition` (`engine_definition_id`),
    INDEX `idx_tenant_key_status` (`tenant_id`, `process_definition_key`, `status`),
    INDEX `idx_tenant_form_version` (`tenant_id`, `form_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 流程发布版本表（不可变）';
