--liquibase formatted sql

--changeset guardfind:oa-cc-manage
--comment: 抄送回执记录抄送人与撤销信息，支持审批中追加抄送与抄送撤销（状态增加 REVOKED）

ALTER TABLE `oa_cc_receipt` ADD COLUMN `cc_by` bigint(20) DEFAULT NULL COMMENT '抄送人（发起时为申请人，审批中为当时处理人）' AFTER `source_task_id`;
ALTER TABLE `oa_cc_receipt` ADD COLUMN `revoke_by` bigint(20) DEFAULT NULL COMMENT '撤销人' AFTER `read_time`;
ALTER TABLE `oa_cc_receipt` ADD COLUMN `revoke_time` datetime DEFAULT NULL COMMENT '撤销时间' AFTER `revoke_by`;
UPDATE `oa_cc_receipt`
   SET `cc_by` = (SELECT a.`applicant_id` FROM `oa_application` a
                   WHERE a.`tenant_id` = `oa_cc_receipt`.`tenant_id` AND a.`id` = `oa_cc_receipt`.`application_id`)
 WHERE `cc_by` IS NULL;
