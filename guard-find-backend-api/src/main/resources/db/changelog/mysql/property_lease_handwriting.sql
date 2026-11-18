-- liquibase formatted sql
-- changeset guardfind:property-lease-handwriting-1 dbms:mysql
ALTER TABLE gf_property_lease ADD published_version bigint NULL;
UPDATE gf_property_lease SET published_version=version WHERE status='PENDING_SIGNATURE';
CREATE TABLE gf_property_lease_signature (
 id bigint PRIMARY KEY, lease_id bigint NOT NULL, signer_id bigint NOT NULL,
 signer_role varchar(20) NOT NULL, signed_name varchar(100) NOT NULL,
 content_hash varchar(64) NOT NULL, contract_snapshot longtext NOT NULL,
 strokes_json longtext NOT NULL, image_data mediumblob NOT NULL,
 signature_hash varchar(64) NOT NULL, record_hash varchar(64) NOT NULL,
 consent_text varchar(1000) NOT NULL, signed_at datetime(6) NOT NULL, seal_data mediumblob NULL,
 UNIQUE KEY uk_lease_signer_role(lease_id,signer_role)
);
CREATE TABLE gf_property_signing_asset (
 id bigint PRIMARY KEY, owner_id bigint NOT NULL, kind varchar(20) NOT NULL,
 strokes_json longtext NULL, image_data mediumblob NOT NULL, update_time datetime NOT NULL,
 UNIQUE KEY uk_signing_asset_owner(owner_id,kind)
);
