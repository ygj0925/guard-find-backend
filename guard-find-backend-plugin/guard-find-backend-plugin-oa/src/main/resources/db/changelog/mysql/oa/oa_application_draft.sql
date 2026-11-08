--liquibase formatted sql

--changeset guardfind:oa-application-draft
--comment: OA 申请草稿（与申请主表分离，提交成功后在同一事务内软删除）

CREATE TABLE IF NOT EXISTS `oa_application_draft` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `applicant_id`         bigint(20)    NOT NULL                    COMMENT '草稿所属人',
    `release_id`           bigint(20)    NOT NULL                    COMMENT '流程发布版本 ID',
    `title`                varchar(200)  DEFAULT NULL                COMMENT '申请标题',
    `form_data`            mediumtext    DEFAULT NULL                COMMENT '表单填写值（JSON，不做必填校验）',
    `assignees_json`       text          DEFAULT NULL                COMMENT '审批人来源绑定（JSON）',
    `cc_user_ids_json`     varchar(1000) DEFAULT NULL                COMMENT '抄送人 ID（JSON）',
    `version`              int           NOT NULL DEFAULT 0          COMMENT '乐观锁版本',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    `deleted`              bigint(20)    NOT NULL DEFAULT 0          COMMENT '是否已删除（0：否；id：是）',
    PRIMARY KEY (`id`),
    INDEX `idx_tenant_applicant` (`tenant_id`, `applicant_id`, `deleted`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 申请草稿表';
