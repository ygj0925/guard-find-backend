--liquibase formatted sql

--changeset guardfind:oa-document
--comment: 公文知识试点：公文/知识台账与版本、按发布范围快照生成的阅读回执（阅读与签收时间），撤稿只改状态不删除版本与回执

CREATE TABLE IF NOT EXISTS `oa_document` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `doc_no`               varchar(32)   DEFAULT NULL                COMMENT '文号',
    `doc_type`             varchar(20)   NOT NULL                    COMMENT '类型（NOTICE/KNOWLEDGE）',
    `title`                varchar(200)  NOT NULL                    COMMENT '标题（已发布版本快照，未发布时为送审版本）',
    `category`             varchar(64)   DEFAULT NULL                COMMENT '分类',
    `owner_id`             bigint(20)    NOT NULL                    COMMENT '拟稿人',
    `status`               varchar(20)   NOT NULL                    COMMENT '状态（APPROVING/PUBLISHED/CLOSED/REVOKED）',
    `published_version`    int           NOT NULL DEFAULT 0          COMMENT '已发布版本号（0 为未发布）',
    `latest_version`       int           NOT NULL DEFAULT 1          COMMENT '最新送审版本号',
    `pending_application_id` bigint(20)  DEFAULT NULL                COMMENT '审批中的修订申请',
    `require_ack`          tinyint(1)    NOT NULL DEFAULT 0          COMMENT '已发布版本是否需要签收',
    `publish_time`         datetime      DEFAULT NULL                COMMENT '最近发布时间',
    `revoke_by`            bigint(20)    DEFAULT NULL                COMMENT '撤稿人',
    `revoke_time`          datetime      DEFAULT NULL                COMMENT '撤稿时间',
    `revoke_reason`        varchar(500)  DEFAULT NULL                COMMENT '撤稿原因',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_no` (`tenant_id`, `doc_no`),
    INDEX `idx_tenant_status` (`tenant_id`, `status`, `id`),
    INDEX `idx_tenant_owner` (`tenant_id`, `owner_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 公文与知识台账';

CREATE TABLE IF NOT EXISTS `oa_document_version` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `document_id`          bigint(20)    NOT NULL                    COMMENT '公文',
    `version_no`           int           NOT NULL                    COMMENT '版本号',
    `application_id`       bigint(20)    NOT NULL                    COMMENT 'OA 申请 ID（附件随申请归属）',
    `title`                varchar(200)  NOT NULL                    COMMENT '标题',
    `category`             varchar(64)   DEFAULT NULL                COMMENT '分类',
    `content`              text          NOT NULL                    COMMENT '正文',
    `change_note`          varchar(500)  DEFAULT NULL                COMMENT '修订说明',
    `audience_json`        varchar(4000) NOT NULL                    COMMENT '发布范围（{"all":bool,"depts":[],"users":[]}）',
    `require_ack`          tinyint(1)    NOT NULL DEFAULT 0          COMMENT '是否需要签收',
    `status`               varchar(20)   NOT NULL                    COMMENT '状态（APPROVING/PUBLISHED/REJECTED/WITHDRAWN/CANCELLED）',
    `applicant_id`         bigint(20)    NOT NULL                    COMMENT '送审人',
    `audience_count`       int           NOT NULL DEFAULT 0          COMMENT '发布时生成的回执数',
    `publish_time`         datetime      DEFAULT NULL                COMMENT '发布时间',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_application` (`tenant_id`, `application_id`),
    UNIQUE INDEX `uk_tenant_document_version` (`tenant_id`, `document_id`, `version_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 公文版本（含正文与发布范围快照）';

CREATE TABLE IF NOT EXISTS `oa_document_receipt` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `document_id`          bigint(20)    NOT NULL                    COMMENT '公文',
    `version_no`           int           NOT NULL                    COMMENT '版本号',
    `user_id`              bigint(20)    NOT NULL                    COMMENT '受众用户',
    `read_time`            datetime      DEFAULT NULL                COMMENT '首次阅读时间',
    `ack_time`             datetime      DEFAULT NULL                COMMENT '签收时间',
    `create_time`          datetime      NOT NULL                    COMMENT '生成时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_document_user` (`tenant_id`, `document_id`, `version_no`, `user_id`),
    INDEX `idx_tenant_user` (`tenant_id`, `user_id`, `document_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 公文阅读回执（受众按发布时快照，深链访问以回执为准）';
