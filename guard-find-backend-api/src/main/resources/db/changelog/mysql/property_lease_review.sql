-- liquibase formatted sql
-- changeset guardfind:property-lease-review-1 dbms:mysql
CREATE TABLE gf_property_lease_review (
 id bigint PRIMARY KEY, lease_id bigint NOT NULL, published_version bigint NOT NULL,
 content_hash varchar(64) NOT NULL, author_id bigint NOT NULL, author_role varchar(20) NOT NULL,
 clause_label varchar(120) NOT NULL, content varchar(1000) NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'OPEN', reply varchar(1000) NULL, replied_by bigint NULL,
 replied_at datetime NULL, resolved_at datetime NULL, version bigint NOT NULL DEFAULT 1,
 create_time datetime NOT NULL, update_time datetime NOT NULL,
 KEY idx_lease_review_round(lease_id,published_version,status)
);
