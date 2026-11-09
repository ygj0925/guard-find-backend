--liquibase formatted sql

--changeset guardfind:oa-asset
--comment: 资产用品试点：用品档案与库存流水（库存不可为负）、领用申请与发放、资产卡片与变动日志、报废申请与执行、盘点单与盘点明细

CREATE TABLE IF NOT EXISTS `oa_supply_item` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `code`                 varchar(32)   NOT NULL                    COMMENT '用品编码',
    `name`                 varchar(100)  NOT NULL                    COMMENT '名称',
    `spec`                 varchar(200)  DEFAULT NULL                COMMENT '规格',
    `unit`                 varchar(16)   NOT NULL                    COMMENT '单位',
    `stock`                int           NOT NULL DEFAULT 0          COMMENT '当前库存（只经流水变动）',
    `safety_stock`         int           NOT NULL DEFAULT 0          COMMENT '安全库存',
    `status`               varchar(20)   NOT NULL DEFAULT 'ENABLED'  COMMENT '状态（ENABLED/DISABLED）',
    `version`              int           NOT NULL DEFAULT 0          COMMENT '乐观锁版本',
    `create_user`          bigint(20)    NOT NULL                    COMMENT '创建人',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_user`          bigint(20)    DEFAULT NULL                COMMENT '修改人',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_code` (`tenant_id`, `code`),
    CONSTRAINT `ck_supply_stock` CHECK (`stock` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 办公用品档案';

CREATE TABLE IF NOT EXISTS `oa_supply_ledger` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `item_id`              bigint(20)    NOT NULL                    COMMENT '用品',
    `action`               varchar(20)   NOT NULL                    COMMENT '动作（IN/OUT/ADJUST）',
    `quantity`             int           NOT NULL                    COMMENT '变动数量（出库为负）',
    `balance_after`        int           NOT NULL                    COMMENT '变动后库存',
    `ref_type`             varchar(20)   NOT NULL                    COMMENT '来源（MANUAL/PURCHASE/ISSUE/STOCKTAKE）',
    `ref_id`               bigint(20)    DEFAULT NULL                COMMENT '来源单据',
    `request_id`           varchar(64)   DEFAULT NULL                COMMENT '手工出入库幂等请求号',
    `operator_id`          bigint(20)    NOT NULL                    COMMENT '操作人',
    `remark`               varchar(500)  DEFAULT NULL                COMMENT '备注',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_request` (`tenant_id`, `request_id`),
    INDEX `idx_tenant_item` (`tenant_id`, `item_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 用品库存流水';

CREATE TABLE IF NOT EXISTS `oa_supply_request` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `application_id`       bigint(20)    NOT NULL                    COMMENT 'OA 申请 ID',
    `applicant_id`         bigint(20)    NOT NULL                    COMMENT '申请人',
    `item_count`           int           NOT NULL                    COMMENT '明细行数',
    `total_quantity`       int           NOT NULL                    COMMENT '申领总数量',
    `status`               varchar(20)   NOT NULL                    COMMENT '状态（APPROVING/PENDING_ISSUE/ISSUED/CLOSED）',
    `issue_request_id`     varchar(64)   DEFAULT NULL                COMMENT '发放幂等请求号',
    `issuer_id`            bigint(20)    DEFAULT NULL                COMMENT '发放人',
    `issued_time`          datetime      DEFAULT NULL                COMMENT '发放时间',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_application` (`tenant_id`, `application_id`),
    UNIQUE INDEX `uk_tenant_issue_request` (`tenant_id`, `issue_request_id`),
    INDEX `idx_tenant_status` (`tenant_id`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 用品领用单（审批通过不扣库存，发放时扣减）';

CREATE TABLE IF NOT EXISTS `oa_supply_request_item` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `supply_request_id`    bigint(20)    NOT NULL                    COMMENT '领用单',
    `item_id`              bigint(20)    NOT NULL                    COMMENT '用品',
    `quantity`             int           NOT NULL                    COMMENT '申领数量',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_request_item` (`tenant_id`, `supply_request_id`, `item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 用品领用明细';

CREATE TABLE IF NOT EXISTS `oa_asset` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `asset_no`             varchar(32)   DEFAULT NULL                COMMENT '资产编号',
    `name`                 varchar(100)  NOT NULL                    COMMENT '名称',
    `category`             varchar(32)   DEFAULT NULL                COMMENT '分类',
    `spec`                 varchar(200)  DEFAULT NULL                COMMENT '规格',
    `status`               varchar(20)   NOT NULL                    COMMENT '状态（IN_STOCK/IN_USE/REPAIR/SCRAPPED）',
    `holder_id`            bigint(20)    DEFAULT NULL                COMMENT '使用人',
    `dept_id`              bigint(20)    DEFAULT NULL                COMMENT '使用部门',
    `location`             varchar(100)  DEFAULT NULL                COMMENT '存放位置',
    `original_value`       decimal(14,2) NOT NULL DEFAULT 0          COMMENT '原值',
    `purchase_id`          bigint(20)    DEFAULT NULL                COMMENT '来源采购单',
    `acquired_date`        date          NOT NULL                    COMMENT '入账日期',
    `version`              int           NOT NULL DEFAULT 0          COMMENT '乐观锁版本',
    `create_user`          bigint(20)    NOT NULL                    COMMENT '创建人',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_asset_no` (`tenant_id`, `asset_no`),
    INDEX `idx_tenant_status` (`tenant_id`, `status`, `id`),
    INDEX `idx_tenant_holder` (`tenant_id`, `holder_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 资产卡片';

CREATE TABLE IF NOT EXISTS `oa_asset_log` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `asset_id`             bigint(20)    NOT NULL                    COMMENT '资产',
    `action`               varchar(20)   NOT NULL                    COMMENT '动作（IN/ASSIGN/RETURN/TRANSFER/REPAIR/REPAIRED/SCRAP/CHECK）',
    `from_status`          varchar(20)   DEFAULT NULL                COMMENT '变动前状态',
    `to_status`            varchar(20)   NOT NULL                    COMMENT '变动后状态',
    `from_holder_id`       bigint(20)    DEFAULT NULL                COMMENT '变动前使用人',
    `to_holder_id`         bigint(20)    DEFAULT NULL                COMMENT '变动后使用人',
    `location`             varchar(100)  DEFAULT NULL                COMMENT '变动后位置',
    `ref_type`             varchar(20)   NOT NULL                    COMMENT '来源（MANUAL/PURCHASE/SCRAP/STOCKTAKE）',
    `ref_id`               bigint(20)    DEFAULT NULL                COMMENT '来源单据',
    `request_id`           varchar(64)   DEFAULT NULL                COMMENT '手工操作幂等请求号',
    `operator_id`          bigint(20)    NOT NULL                    COMMENT '操作人',
    `remark`               varchar(500)  DEFAULT NULL                COMMENT '备注',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_request` (`tenant_id`, `request_id`),
    INDEX `idx_tenant_asset` (`tenant_id`, `asset_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 资产变动日志';

CREATE TABLE IF NOT EXISTS `oa_asset_scrap` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `application_id`       bigint(20)    NOT NULL                    COMMENT 'OA 申请 ID',
    `applicant_id`         bigint(20)    NOT NULL                    COMMENT '申请人',
    `asset_id`             bigint(20)    NOT NULL                    COMMENT '资产',
    `status`               varchar(20)   NOT NULL                    COMMENT '状态（APPROVING/PENDING_SCRAP/SCRAPPED/CLOSED）',
    `executor_id`          bigint(20)    DEFAULT NULL                COMMENT '执行报废人',
    `executed_time`        datetime      DEFAULT NULL                COMMENT '执行时间',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_application` (`tenant_id`, `application_id`),
    INDEX `idx_tenant_asset` (`tenant_id`, `asset_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 资产报废单（审批通过后由管理员执行）';

CREATE TABLE IF NOT EXISTS `oa_stocktake` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `title`                varchar(100)  NOT NULL                    COMMENT '盘点名称',
    `status`               varchar(20)   NOT NULL                    COMMENT '状态（OPEN/CLOSED）',
    `create_user`          bigint(20)    NOT NULL                    COMMENT '发起人',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `closed_by`            bigint(20)    DEFAULT NULL                COMMENT '结束人',
    `closed_time`          datetime      DEFAULT NULL                COMMENT '结束时间',
    PRIMARY KEY (`id`),
    INDEX `idx_tenant_status` (`tenant_id`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 盘点单（账面数量在创建时快照）';

CREATE TABLE IF NOT EXISTS `oa_stocktake_line` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `stocktake_id`         bigint(20)    NOT NULL                    COMMENT '盘点单',
    `target_type`          varchar(20)   NOT NULL                    COMMENT '对象（SUPPLY/ASSET）',
    `target_id`            bigint(20)    NOT NULL                    COMMENT '用品或资产',
    `target_code`          varchar(32)   DEFAULT NULL                COMMENT '编码快照',
    `target_name`          varchar(100)  NOT NULL                    COMMENT '名称快照',
    `book_qty`             int           NOT NULL                    COMMENT '账面数量',
    `counted_qty`          int           DEFAULT NULL                COMMENT '实盘数量',
    `adjusted_qty`         int           DEFAULT NULL                COMMENT '结束时调整的差异',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_stocktake_target` (`tenant_id`, `stocktake_id`, `target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 盘点明细';
