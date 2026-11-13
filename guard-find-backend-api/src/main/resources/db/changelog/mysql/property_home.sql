-- liquibase formatted sql

-- changeset guardfind:property-home-1 dbms:mysql
ALTER TABLE gf_property
    ADD COLUMN price_unit varchar(10) DEFAULT NULL,
    ADD COLUMN recommended tinyint NOT NULL DEFAULT 0;
UPDATE gf_property SET price_unit = CASE WHEN price_type = 'rent' THEN 'month' ELSE NULL END;
CREATE INDEX idx_property_recommended ON gf_property(status, deleted, recommended, create_time);

CREATE TABLE gf_property_home (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL DEFAULT 0,
    content_json json NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_property_home_singleton CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台房源首页配置';
