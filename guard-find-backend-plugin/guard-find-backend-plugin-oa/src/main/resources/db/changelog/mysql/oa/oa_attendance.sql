--liquibase formatted sql

--changeset guardfind:oa-attendance
--comment: 假勤：班次、排班、考勤记录（受控导入/补卡写入）、导入批次（按请求号幂等）与补卡/加班/外出申请单

CREATE TABLE IF NOT EXISTS `oa_shift` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `code`                 varchar(32)   NOT NULL                    COMMENT '班次编码',
    `name`                 varchar(64)   NOT NULL                    COMMENT '班次名称',
    `start_time`           time          NOT NULL                    COMMENT '上班时间',
    `end_time`             time          NOT NULL                    COMMENT '下班时间（不跨天，晚于上班时间）',
    `grace_minutes`        int           NOT NULL DEFAULT 0          COMMENT '迟到宽限分钟',
    `status`               varchar(20)   NOT NULL DEFAULT 'ENABLED'  COMMENT '状态（ENABLED/DISABLED）',
    `remark`               varchar(500)  DEFAULT NULL                COMMENT '说明',
    `version`              int           NOT NULL DEFAULT 0          COMMENT '乐观锁版本',
    `create_user`          bigint(20)    NOT NULL                    COMMENT '创建人',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_user`          bigint(20)    DEFAULT NULL                COMMENT '修改人',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_code` (`tenant_id`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 考勤班次';

CREATE TABLE IF NOT EXISTS `oa_schedule` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `user_id`              bigint(20)    NOT NULL                    COMMENT '员工',
    `work_date`            date          NOT NULL                    COMMENT '日期',
    `shift_id`             bigint(20)    DEFAULT NULL                COMMENT '班次（NULL 表示休息）',
    `create_user`          bigint(20)    NOT NULL                    COMMENT '创建人',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_user_date` (`tenant_id`, `user_id`, `work_date`),
    INDEX `idx_tenant_date` (`tenant_id`, `work_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 排班';

CREATE TABLE IF NOT EXISTS `oa_attendance_import` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `request_id`           varchar(64)   NOT NULL                    COMMENT '请求号（幂等）',
    `file_name`            varchar(200)  DEFAULT NULL                COMMENT '来源文件名',
    `total_rows`           int           NOT NULL                    COMMENT '行数',
    `inserted_rows`        int           NOT NULL                    COMMENT '新增记录数',
    `updated_rows`         int           NOT NULL                    COMMENT '更新记录数',
    `kept_corrections`     int           NOT NULL DEFAULT 0          COMMENT '因已补卡而保留的打卡数',
    `operator_id`          bigint(20)    NOT NULL                    COMMENT '导入人',
    `create_time`          datetime      NOT NULL                    COMMENT '导入时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_request` (`tenant_id`, `request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 考勤导入批次';

CREATE TABLE IF NOT EXISTS `oa_attendance_record` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `user_id`              bigint(20)    NOT NULL                    COMMENT '员工',
    `work_date`            date          NOT NULL                    COMMENT '日期',
    `check_in`             datetime      DEFAULT NULL                COMMENT '上班打卡',
    `check_out`            datetime      DEFAULT NULL                COMMENT '下班打卡',
    `check_in_source`      varchar(20)   DEFAULT NULL                COMMENT '上班来源（IMPORT/CORRECTION）',
    `check_out_source`     varchar(20)   DEFAULT NULL                COMMENT '下班来源（IMPORT/CORRECTION）',
    `import_id`            bigint(20)    DEFAULT NULL                COMMENT '最近一次导入批次',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_user_date` (`tenant_id`, `user_id`, `work_date`),
    INDEX `idx_tenant_date` (`tenant_id`, `work_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 考勤记录';

CREATE TABLE IF NOT EXISTS `oa_attendance_request` (
    `id`                   bigint(20)    AUTO_INCREMENT              COMMENT 'ID',
    `tenant_id`            bigint(20)    NOT NULL DEFAULT 0          COMMENT '租户ID',
    `application_id`       bigint(20)    NOT NULL                    COMMENT 'OA 申请 ID',
    `applicant_id`         bigint(20)    NOT NULL                    COMMENT '申请人',
    `kind`                 varchar(20)   NOT NULL                    COMMENT '类型（CORRECTION/OVERTIME/OUTING）',
    `work_date`            date          NOT NULL                    COMMENT '归属日期',
    `start_time`           datetime      NOT NULL                    COMMENT '开始时间（补卡为补卡时间）',
    `end_time`             datetime      NOT NULL                    COMMENT '结束时间（补卡同开始时间）',
    `hours`                decimal(5,1)  NOT NULL DEFAULT 0          COMMENT '时长（小时）',
    `check_type`           varchar(8)    DEFAULT NULL                COMMENT '补卡类型（IN/OUT）',
    `destination`          varchar(100)  DEFAULT NULL                COMMENT '外出地点',
    `status`               varchar(20)   NOT NULL                    COMMENT '状态（APPROVING/APPROVED/CLOSED）',
    `create_time`          datetime      NOT NULL                    COMMENT '创建时间',
    `update_time`          datetime      DEFAULT NULL                COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_tenant_application` (`tenant_id`, `application_id`),
    INDEX `idx_tenant_applicant` (`tenant_id`, `applicant_id`, `start_time`),
    INDEX `idx_tenant_kind_status` (`tenant_id`, `kind`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 补卡/加班/外出申请单（随申请同事务写入）';
