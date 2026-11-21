-- liquibase formatted sql
-- changeset guardfind:property-lease-signature-identity-1 dbms:mysql
ALTER TABLE gf_property_lease_signature ADD verification_id bigint NULL;
