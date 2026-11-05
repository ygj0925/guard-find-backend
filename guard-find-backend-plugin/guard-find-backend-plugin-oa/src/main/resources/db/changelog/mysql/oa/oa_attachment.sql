--liquibase formatted sql

--changeset guardfind:oa-attachment-link
--comment: OA 附件归属与扫描状态（文件本体由受控存储管理，裸 fileId 不构成授权）

CREATE TABLE IF NOT EXISTS `oa_attachment_link` (
    `id`             bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`      bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `application_id` bigint(20)    DEFAULT NULL                COMMENT '申请 ID',
    `business_type`  varchar(64)   DEFAULT NULL                COMMENT '业务类型（与申请一致）',
    `business_key`   varchar(128)  DEFAULT NULL                COMMENT '业务单号',
    `file_id`        bigint(20)    NOT NULL                    COMMENT '存储文件 ID',
    `file_name`      varchar(255)  DEFAULT NULL                COMMENT '原始文件名快照',
    `file_size`      bigint(20)    DEFAULT NULL                COMMENT '文件大小（字节）',
    `content_type`   varchar(100)  DEFAULT NULL                COMMENT 'MIME 类型',
    `category`       varchar(32)   DEFAULT NULL                COMMENT '附件用途（FORM/APPROVAL/COMMENT）',
    `scan_status`    varchar(20)   NOT NULL DEFAULT 'PENDING'  COMMENT '扫描状态（PENDING/CLEAN/INFECTED/FAILED）',
    `available`      tinyint(1)    NOT NULL DEFAULT 0          COMMENT '是否可下载（0：否；1：是）',
    `create_user`    bigint(20)    NOT NULL                    COMMENT '上传人',
    `create_time`    datetime      NOT NULL                    COMMENT '上传时间',
    `update_user`    bigint(20)    DEFAULT NULL                COMMENT '修改人',
    `update_time`    datetime      DEFAULT NULL                COMMENT '修改时间',
    `deleted`        bigint(20)    NOT NULL DEFAULT 0          COMMENT '是否已删除（0：否；id：是）',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_file` (`tenant_id`, `file_id`, `deleted`),
    INDEX `idx_tenant_application` (`tenant_id`, `application_id`),
    INDEX `idx_tenant_business` (`tenant_id`, `business_type`, `business_key`),
    INDEX `idx_tenant_scan` (`tenant_id`, `scan_status`, `available`),
    INDEX `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 附件归属表';
