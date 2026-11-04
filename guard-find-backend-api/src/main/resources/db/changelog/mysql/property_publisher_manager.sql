-- liquibase formatted sql

-- changeset guardfind:property-publisher-manager-1
-- comment 房源发布权限收口到房源管家前，先把已发布过房源的小程序用户提升为管家，避免存量发布者再也改不动自己的房源
UPDATE gf_miniapp_user
SET is_property_manager = 1
WHERE is_property_manager <> 1
  AND id IN (SELECT user_id FROM gf_property WHERE user_id IS NOT NULL);
