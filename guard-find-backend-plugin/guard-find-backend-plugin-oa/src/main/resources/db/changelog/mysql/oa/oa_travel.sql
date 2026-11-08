--liquibase formatted sql

--changeset guardfind:oa-travel
--comment: 出差与借款试点：出差单（预算）、借款单（发放/余额/还清）与借款流水（发放/报销核销/还款，核销按报销唯一、还款按请求号幂等）

CREATE TABLE IF NOT EXISTS `oa_travel` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `application_id`       bigint(20)    NOT NULL                    COMMENT 'OA 申请 ID',
    `applicant_id`         bigint(20)    NOT NULL                    COMMENT '申请人',
    `travel_no`            varchar(32)   DEFAULT NULL                COMMENT '出差单号（借款/报销按此关联）',
    `destination`          varchar(100)  NOT NULL                    COMMENT '目的地',
    `start_date`           date          NOT NULL                    COMMENT '开始日期',
    `end_date`             date          NOT NULL                    COMMENT '结束日期',
    `days`                 int           NOT NULL                    COMMENT '自然日天数',
    `budget_amount`        decimal(14,2) NOT NULL                    COMMENT '预算金额',
    `status`               varchar(20)   NOT NULL                    COMMENT '状态（APPROVING/APPROVED/CLOSED）',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_application` (`tenant_id`, `application_id`),
    UNIQUE INDEX `uk_tenant_travel_no` (`tenant_id`, `travel_no`),
    INDEX `idx_tenant_applicant` (`tenant_id`, `applicant_id`, `start_date`),
    INDEX `idx_tenant_status` (`tenant_id`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 出差单（随申请同事务写入）';

CREATE TABLE IF NOT EXISTS `oa_loan` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `application_id`       bigint(20)    NOT NULL                    COMMENT 'OA 申请 ID',
    `applicant_id`         bigint(20)    NOT NULL                    COMMENT '借款人',
    `travel_id`            bigint(20)    DEFAULT NULL                COMMENT '关联出差单',
    `amount`               decimal(14,2) NOT NULL                    COMMENT '借款金额',
    `balance`              decimal(14,2) NOT NULL DEFAULT 0          COMMENT '未还余额（发放后等于借款金额）',
    `status`               varchar(20)   NOT NULL                    COMMENT '状态（APPROVING/PENDING_DISBURSE/OUTSTANDING/SETTLED/CLOSED）',
    `expected_repay_date`  date          DEFAULT NULL                COMMENT '预计还款日期',
    `disburse_date`        date          DEFAULT NULL                COMMENT '发放日期',
    `disburse_method`      varchar(20)   DEFAULT NULL                COMMENT '发放方式',
    `disburse_reference`   varchar(64)   DEFAULT NULL                COMMENT '发放流水号',
    `disburser_id`         bigint(20)    DEFAULT NULL                COMMENT '登记发放的财务人员',
    `disbursed_time`       datetime      DEFAULT NULL                COMMENT '发放登记时间',
    `settled_time`         datetime      DEFAULT NULL                COMMENT '还清时间',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_application` (`tenant_id`, `application_id`),
    INDEX `idx_tenant_travel` (`tenant_id`, `travel_id`),
    INDEX `idx_tenant_applicant` (`tenant_id`, `applicant_id`, `status`),
    INDEX `idx_tenant_status` (`tenant_id`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 借款单（随申请同事务写入）';

CREATE TABLE IF NOT EXISTS `oa_loan_ledger` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `loan_id`              bigint(20)    NOT NULL                    COMMENT '借款单',
    `action`               varchar(20)   NOT NULL                    COMMENT '动作（DISBURSE/WRITE_OFF/REPAY）',
    `amount`               decimal(14,2) NOT NULL                    COMMENT '金额',
    `balance_after`        decimal(14,2) NOT NULL                    COMMENT '动作后余额',
    `expense_id`           bigint(20)    DEFAULT NULL                COMMENT '核销来源报销单',
    `request_id`           varchar(64)   DEFAULT NULL                COMMENT '还款请求号（幂等）',
    `biz_date`             date          DEFAULT NULL                COMMENT '业务日期',
    `method`               varchar(20)   DEFAULT NULL                COMMENT '方式',
    `reference`            varchar(64)   DEFAULT NULL                COMMENT '流水号',
    `operator_id`          bigint(20)    NOT NULL                    COMMENT '操作人',
    `remark`               varchar(500)  DEFAULT NULL                COMMENT '备注',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_loan_expense` (`tenant_id`, `loan_id`, `expense_id`),
    UNIQUE INDEX `uk_tenant_request` (`tenant_id`, `request_id`),
    INDEX `idx_tenant_expense` (`tenant_id`, `expense_id`),
    INDEX `idx_tenant_loan` (`tenant_id`, `loan_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 借款流水';

--changeset guardfind:oa-expense-travel
--comment: 报销关联出差单、超预算标记与借款核销金额

ALTER TABLE `oa_expense`
    ADD COLUMN `travel_id` bigint(20) DEFAULT NULL COMMENT '关联出差单' AFTER `item_count`,
    ADD COLUMN `over_budget` tinyint(1) NOT NULL DEFAULT 0 COMMENT '提交时是否超出出差预算' AFTER `travel_id`,
    ADD COLUMN `write_off_amount` decimal(14,2) NOT NULL DEFAULT 0 COMMENT '支付时核销借款金额' AFTER `paid_amount`,
    ADD INDEX `idx_tenant_travel` (`tenant_id`, `travel_id`);
