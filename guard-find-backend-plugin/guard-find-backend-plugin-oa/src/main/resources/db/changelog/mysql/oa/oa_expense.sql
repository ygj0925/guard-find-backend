--liquibase formatted sql

--changeset guardfind:oa-expense
--comment: 报销试点：费用类型、报销单、明细快照与发票号占用（审批通过后待支付，支付登记与审批分离）

CREATE TABLE IF NOT EXISTS `oa_expense_category` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `code`                 varchar(32)   NOT NULL                    COMMENT '类型编码（明细选项值）',
    `name`                 varchar(64)   NOT NULL                    COMMENT '类型名称',
    `per_item_limit`       decimal(12,2) DEFAULT NULL                COMMENT '单笔上限（空为不限）',
    `invoice_required`     tinyint(1)    NOT NULL DEFAULT 0          COMMENT '明细是否必须填写发票号',
    `status`               varchar(20)   NOT NULL DEFAULT 'ENABLED'  COMMENT '状态（ENABLED/DISABLED）',
    `sort`                 int           NOT NULL DEFAULT 0          COMMENT '排序',
    `remark`               varchar(500)  DEFAULT NULL                COMMENT '说明',
    `version`              int           NOT NULL DEFAULT 0          COMMENT '乐观锁版本',
    `create_user`          bigint(20)    NOT NULL                    COMMENT '创建人',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_user`          bigint(20)    DEFAULT NULL                COMMENT '修改人',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_code` (`tenant_id`, `code`),
    INDEX `idx_tenant_status` (`tenant_id`, `status`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 费用类型表';

CREATE TABLE IF NOT EXISTS `oa_expense` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `application_id`       bigint(20)    NOT NULL                    COMMENT 'OA 申请 ID',
    `applicant_id`         bigint(20)    NOT NULL                    COMMENT '申请人',
    `total_amount`         decimal(14,2) NOT NULL                    COMMENT '明细合计（服务端计算）',
    `item_count`           int           NOT NULL                    COMMENT '明细行数',
    `status`               varchar(20)   NOT NULL                    COMMENT '状态（APPROVING/PENDING_PAYMENT/PAID/CLOSED）',
    `paid_amount`          decimal(14,2) DEFAULT NULL                COMMENT '实付金额',
    `pay_date`             date          DEFAULT NULL                COMMENT '支付日期',
    `pay_method`           varchar(20)   DEFAULT NULL                COMMENT '支付方式（BANK_TRANSFER/CASH/OTHER）',
    `pay_reference`        varchar(64)   DEFAULT NULL                COMMENT '支付流水号',
    `pay_remark`           varchar(500)  DEFAULT NULL                COMMENT '支付备注',
    `payer_id`             bigint(20)    DEFAULT NULL                COMMENT '登记支付的财务人员',
    `paid_time`            datetime      DEFAULT NULL                COMMENT '登记时间',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_application` (`tenant_id`, `application_id`),
    INDEX `idx_tenant_status` (`tenant_id`, `status`, `id`),
    INDEX `idx_tenant_applicant` (`tenant_id`, `applicant_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 报销单（随申请同事务写入）';

CREATE TABLE IF NOT EXISTS `oa_expense_item` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `expense_id`           bigint(20)    NOT NULL                    COMMENT '报销单 ID',
    `application_id`       bigint(20)    NOT NULL                    COMMENT 'OA 申请 ID',
    `line_no`              int           NOT NULL                    COMMENT '行号（从 1 开始）',
    `category_code`        varchar(32)   NOT NULL                    COMMENT '费用类型编码快照',
    `category_name`        varchar(64)   NOT NULL                    COMMENT '费用类型名称快照',
    `amount`               decimal(12,2) NOT NULL                    COMMENT '金额',
    `expense_date`         date          DEFAULT NULL                COMMENT '发生日期',
    `invoice_no`           varchar(64)   DEFAULT NULL                COMMENT '发票号（规范化后）',
    `description`          varchar(500)  DEFAULT NULL                COMMENT '说明',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_expense_line` (`tenant_id`, `expense_id`, `line_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 报销明细快照';

CREATE TABLE IF NOT EXISTS `oa_expense_invoice` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `invoice_no`           varchar(64)   NOT NULL                    COMMENT '发票号（规范化后）',
    `application_id`       bigint(20)    NOT NULL                    COMMENT '占用该发票的申请',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_invoice` (`tenant_id`, `invoice_no`),
    INDEX `idx_tenant_application` (`tenant_id`, `application_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 发票号占用（审批中与已通过的报销占用，驳回/撤回/终止释放）';
