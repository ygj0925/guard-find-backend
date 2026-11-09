--liquibase formatted sql

--changeset guardfind:oa-purchase
--comment: 采购试点：部门年度预算（额度/已用/占用）与预算流水、采购单与明细、下单执行登记、按请求号幂等的验收单与验收明细

CREATE TABLE IF NOT EXISTS `oa_purchase_budget` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `dept_id`              bigint(20)    NOT NULL                    COMMENT '部门',
    `budget_year`          int           NOT NULL                    COMMENT '预算年度',
    `amount`               decimal(14,2) NOT NULL                    COMMENT '年度预算额度',
    `used_amount`          decimal(14,2) NOT NULL DEFAULT 0          COMMENT '已用（审批通过的采购）',
    `occupied_amount`      decimal(14,2) NOT NULL DEFAULT 0          COMMENT '占用（审批中的采购）',
    `remark`               varchar(500)  DEFAULT NULL                COMMENT '备注',
    `version`              int           NOT NULL DEFAULT 0          COMMENT '乐观锁版本',
    `create_user`          bigint(20)    NOT NULL                    COMMENT '创建人',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_user`          bigint(20)    DEFAULT NULL                COMMENT '修改人',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_dept_year` (`tenant_id`, `dept_id`, `budget_year`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 部门年度采购预算';

CREATE TABLE IF NOT EXISTS `oa_purchase_budget_ledger` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `budget_id`            bigint(20)    NOT NULL                    COMMENT '预算',
    `action`               varchar(20)   NOT NULL                    COMMENT '动作（GRANT/ADJUST/OCCUPY/RELEASE/USE）',
    `amount`               decimal(14,2) NOT NULL                    COMMENT '金额（ADJUST 为增减额）',
    `purchase_id`          bigint(20)    DEFAULT NULL                COMMENT '关联采购单',
    `operator_id`          bigint(20)    NOT NULL                    COMMENT '操作人',
    `remark`               varchar(500)  DEFAULT NULL                COMMENT '备注',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_purchase_action` (`tenant_id`, `purchase_id`, `action`),
    INDEX `idx_tenant_budget` (`tenant_id`, `budget_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 采购预算流水（每张采购单每种动作至多一条）';

CREATE TABLE IF NOT EXISTS `oa_purchase` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `application_id`       bigint(20)    NOT NULL                    COMMENT 'OA 申请 ID',
    `applicant_id`         bigint(20)    NOT NULL                    COMMENT '申请人',
    `dept_id`              bigint(20)    NOT NULL                    COMMENT '申请部门',
    `budget_id`            bigint(20)    NOT NULL                    COMMENT '占用的部门预算',
    `purchase_no`          varchar(32)   DEFAULT NULL                COMMENT '采购单号',
    `total_amount`         decimal(14,2) NOT NULL                    COMMENT '合计金额（服务端按明细重算）',
    `item_count`           int           NOT NULL                    COMMENT '明细行数',
    `expected_date`        date          DEFAULT NULL                COMMENT '期望到货日期',
    `status`               varchar(20)   NOT NULL                    COMMENT '状态（APPROVING/PENDING_ORDER/ORDERED/PARTIAL/RECEIVED/CLOSED）',
    `supplier`             varchar(100)  DEFAULT NULL                COMMENT '供应商',
    `order_no`             varchar(64)   DEFAULT NULL                COMMENT '外部订单号',
    `order_date`           date          DEFAULT NULL                COMMENT '下单日期',
    `orderer_id`           bigint(20)    DEFAULT NULL                COMMENT '登记下单人',
    `ordered_time`         datetime      DEFAULT NULL                COMMENT '下单登记时间',
    `received_time`        datetime      DEFAULT NULL                COMMENT '全部验收时间',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_application` (`tenant_id`, `application_id`),
    UNIQUE INDEX `uk_tenant_purchase_no` (`tenant_id`, `purchase_no`),
    INDEX `idx_tenant_applicant` (`tenant_id`, `applicant_id`, `id`),
    INDEX `idx_tenant_status` (`tenant_id`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 采购单（随申请同事务写入）';

CREATE TABLE IF NOT EXISTS `oa_purchase_item` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `purchase_id`          bigint(20)    NOT NULL                    COMMENT '采购单',
    `line_no`              int           NOT NULL                    COMMENT '行号',
    `item_name`            varchar(100)  NOT NULL                    COMMENT '物品名称',
    `category`             varchar(20)   NOT NULL                    COMMENT '类别（SUPPLY 入用品库存/ASSET 生成资产卡片/OTHER 仅验收）',
    `supply_code`          varchar(32)   DEFAULT NULL                COMMENT '用品编码（SUPPLY 必填）',
    `spec`                 varchar(200)  DEFAULT NULL                COMMENT '规格',
    `quantity`             int           NOT NULL                    COMMENT '采购数量',
    `unit_price`           decimal(14,2) NOT NULL                    COMMENT '单价',
    `amount`               decimal(14,2) NOT NULL                    COMMENT '行金额',
    `received_qty`         int           NOT NULL DEFAULT 0          COMMENT '已验收数量',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_purchase_line` (`tenant_id`, `purchase_id`, `line_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 采购明细';

CREATE TABLE IF NOT EXISTS `oa_purchase_receipt` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `purchase_id`          bigint(20)    NOT NULL                    COMMENT '采购单',
    `request_id`           varchar(64)   NOT NULL                    COMMENT '幂等请求号',
    `receive_date`         date          NOT NULL                    COMMENT '验收日期',
    `receiver_id`          bigint(20)    NOT NULL                    COMMENT '验收人',
    `remark`               varchar(500)  DEFAULT NULL                COMMENT '备注',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_request` (`tenant_id`, `request_id`),
    INDEX `idx_tenant_purchase` (`tenant_id`, `purchase_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 采购验收单';

CREATE TABLE IF NOT EXISTS `oa_purchase_receipt_line` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `receipt_id`           bigint(20)    NOT NULL                    COMMENT '验收单',
    `item_id`              bigint(20)    NOT NULL                    COMMENT '采购明细',
    `quantity`             int           NOT NULL                    COMMENT '本次验收数量',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_receipt_item` (`tenant_id`, `receipt_id`, `item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 采购验收明细';
