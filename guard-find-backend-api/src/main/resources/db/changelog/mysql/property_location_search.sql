-- liquibase formatted sql
-- changeset guardfind:property-location-search-1
ALTER TABLE gf_property ADD COLUMN public_latitude DECIMAL(10,7) NULL COMMENT '公开小区入口纬度 GCJ-02', ADD COLUMN public_longitude DECIMAL(10,7) NULL COMMENT '公开小区入口经度 GCJ-02', ADD COLUMN location_label VARCHAR(200) NULL COMMENT '公开位置名称', ADD COLUMN region_code VARCHAR(6) NULL COMMENT '民政部行政区划代码';
CREATE INDEX idx_property_map ON gf_property(status, deleted, public_latitude, public_longitude);
CREATE INDEX idx_property_community ON gf_property(city, community);
