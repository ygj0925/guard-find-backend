--liquibase formatted sql

--changeset guardfind:oa-form-draft
--comment: 表单草稿 schema 与已发布版本分离，发布时才写入不可变版本快照

ALTER TABLE `oa_form`
    ADD COLUMN `draft_schema_json` mediumtext DEFAULT NULL COMMENT '草稿 schema（可编辑）' AFTER `description`,
    ADD COLUMN `draft_schema_digest` varchar(64) DEFAULT NULL COMMENT '草稿 schema SHA-256' AFTER `draft_schema_json`;
