-- liquibase formatted sql

-- changeset guardfind:property-optional-owner-1
-- comment 后台新增房源允许不绑定小程序房东，相关看房预约由后台统一处理
ALTER TABLE gf_property MODIFY COLUMN user_id bigint(20) NULL COMMENT '房东小程序用户 ID，空值表示后台管理';
ALTER TABLE gf_property_booking MODIFY COLUMN owner_id bigint(20) NULL COMMENT '房东小程序用户 ID，空值表示后台管理';
