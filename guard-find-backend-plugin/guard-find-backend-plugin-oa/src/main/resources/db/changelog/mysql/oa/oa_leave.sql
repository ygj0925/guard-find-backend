--liquibase formatted sql

--changeset guardfind:oa-process-business-type
--comment: 流程模型业务类型（创建后不可修改；GENERIC 为通用审批，LEAVE/EXPENSE 由业务钩子接管额度与状态）

ALTER TABLE `oa_process_model`
    ADD COLUMN `business_type` varchar(32) NOT NULL DEFAULT 'GENERIC' COMMENT '业务类型（GENERIC/LEAVE/EXPENSE）' AFTER `category`;

--changeset guardfind:oa-leave
--comment: 请假试点：假期类型、工作日历、年度余额、请假单与额度台账

CREATE TABLE IF NOT EXISTS `oa_leave_type` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `code`                 varchar(32)   NOT NULL                    COMMENT '类型编码（表单选项值）',
    `name`                 varchar(64)   NOT NULL                    COMMENT '类型名称',
    `balance_required`     tinyint(1)    NOT NULL DEFAULT 1          COMMENT '是否扣减年度余额',
    `max_days_per_request` decimal(6,1)  DEFAULT NULL                COMMENT '单次最多天数（空为不限）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 假期类型表';

CREATE TABLE IF NOT EXISTS `oa_work_calendar` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `calendar_date`        date          NOT NULL                    COMMENT '日期',
    `day_type`             varchar(20)   NOT NULL                    COMMENT '日期类型（WORKDAY：调休上班；HOLIDAY：节假日）',
    `name`                 varchar(64)   DEFAULT NULL                COMMENT '名称（如 国庆节）',
    `create_user`          bigint(20)    NOT NULL                    COMMENT '创建人',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_date` (`tenant_id`, `calendar_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 工作日历例外表（未登记日期按周一至周五上班）';

CREATE TABLE IF NOT EXISTS `oa_leave_balance` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `user_id`              bigint(20)    NOT NULL                    COMMENT '员工用户 ID',
    `leave_type_id`        bigint(20)    NOT NULL                    COMMENT '假期类型 ID',
    `period_year`          int           NOT NULL                    COMMENT '年度',
    `total_days`           decimal(8,1)  NOT NULL DEFAULT 0          COMMENT '发放总额',
    `used_days`            decimal(8,1)  NOT NULL DEFAULT 0          COMMENT '已扣减',
    `occupied_days`        decimal(8,1)  NOT NULL DEFAULT 0          COMMENT '审批中占用',
    `version`              int           NOT NULL DEFAULT 0          COMMENT '变更次数',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_user_type_year` (`tenant_id`, `user_id`, `leave_type_id`, `period_year`),
    INDEX `idx_tenant_year` (`tenant_id`, `period_year`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 假期年度余额表';

CREATE TABLE IF NOT EXISTS `oa_leave_request` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `application_id`       bigint(20)    NOT NULL                    COMMENT 'OA 申请 ID',
    `applicant_id`         bigint(20)    NOT NULL                    COMMENT '申请人',
    `leave_type_id`        bigint(20)    NOT NULL                    COMMENT '假期类型 ID',
    `leave_type_code`      varchar(32)   NOT NULL                    COMMENT '假期类型编码快照',
    `leave_type_name`      varchar(64)   NOT NULL                    COMMENT '假期类型名称快照',
    `start_date`           date          NOT NULL                    COMMENT '开始日期',
    `start_half`           varchar(2)    NOT NULL                    COMMENT '开始时段（AM/PM）',
    `end_date`             date          NOT NULL                    COMMENT '结束日期',
    `end_half`             varchar(2)    NOT NULL                    COMMENT '结束时段（AM/PM）',
    `days`                 decimal(8,1)  NOT NULL                    COMMENT '按工作日历折算的天数',
    `period_year`          int           NOT NULL                    COMMENT '扣减年度',
    `balance_id`           bigint(20)    DEFAULT NULL                COMMENT '占用的余额 ID（不扣余额的类型为空）',
    `status`               varchar(20)   NOT NULL                    COMMENT '状态（OCCUPIED/DEDUCTED/RELEASED/RECORDED）',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_application` (`tenant_id`, `application_id`),
    INDEX `idx_tenant_applicant` (`tenant_id`, `applicant_id`, `start_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 请假单（随申请同事务写入）';

CREATE TABLE IF NOT EXISTS `oa_leave_ledger` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `balance_id`           bigint(20)    NOT NULL                    COMMENT '余额 ID',
    `application_id`       bigint(20)    DEFAULT NULL                COMMENT '关联申请（发放/调整为空）',
    `action`               varchar(20)   NOT NULL                    COMMENT '动作（GRANT/OCCUPY/RELEASE/DEDUCT）',
    `days`                 decimal(8,1)  NOT NULL                    COMMENT '本次变动天数（发放可为负）',
    `total_after`          decimal(8,1)  NOT NULL                    COMMENT '变动后总额',
    `used_after`           decimal(8,1)  NOT NULL                    COMMENT '变动后已扣减',
    `occupied_after`       decimal(8,1)  NOT NULL                    COMMENT '变动后占用',
    `operator_id`          bigint(20)    NOT NULL                    COMMENT '操作人',
    `remark`               varchar(500)  DEFAULT NULL                COMMENT '说明',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_application_action` (`tenant_id`, `application_id`, `action`),
    INDEX `idx_tenant_balance` (`tenant_id`, `balance_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 假期额度台账（申请动作按申请+动作唯一，防重复扣减）';
