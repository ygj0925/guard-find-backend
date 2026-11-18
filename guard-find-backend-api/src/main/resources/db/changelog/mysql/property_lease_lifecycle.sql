-- liquibase formatted sql
-- changeset guardfind:property-lease-lifecycle-1 dbms:mysql
ALTER TABLE gf_property_lease ADD signature_deadline datetime NULL;
UPDATE gf_property_lease SET signature_deadline=CAST(CONCAT(end_date,' 23:59:59') AS DATETIME) WHERE status='PENDING_SIGNATURE';
UPDATE gf_property_lease SET renewal_status='CANCELED',renewal_lease_id=NULL,version=version+1
 WHERE renewal_status='APPROVED' AND renewal_lease_id IN
 (SELECT id FROM (SELECT id FROM gf_property_lease WHERE status='TERMINATED') ended_renewals);
CREATE TABLE gf_property_lease_bill (
 id bigint PRIMARY KEY, lease_id bigint NOT NULL, kind varchar(20) NOT NULL, title varchar(200) NOT NULL,
 period_start date NOT NULL, period_end date NOT NULL, due_date date NOT NULL, amount decimal(12,2) NOT NULL,
 status varchar(30) NOT NULL DEFAULT 'UNPAID', version bigint NOT NULL DEFAULT 0,
 payment_reference varchar(1000) NULL, payment_file_id bigint NULL, paid_date date NULL, confirmed_at datetime NULL,
 create_time datetime NOT NULL, update_time datetime NOT NULL,
 INDEX idx_lease_bill(lease_id,id), INDEX idx_lease_bill_due(status,due_date)
);
CREATE TABLE gf_property_lease_document (
 id bigint PRIMARY KEY, lease_id bigint NOT NULL, kind varchar(20) NOT NULL,
 content_json longtext NOT NULL, content_hash varchar(64) NOT NULL,
 status varchar(30) NOT NULL DEFAULT 'PENDING', version bigint NOT NULL DEFAULT 0,
 tenant_confirmed_at datetime NULL, landlord_confirmed_at datetime NULL,
 refund_reference varchar(1000) NULL, refund_date date NULL, refund_received_at datetime NULL,
 create_time datetime NOT NULL, update_time datetime NOT NULL,
 INDEX idx_lease_document(lease_id,kind,id)
);
CREATE TABLE gf_property_lease_file (
 id bigint PRIMARY KEY, lease_id bigint NOT NULL, mime_type varchar(40) NOT NULL, file_data mediumblob NOT NULL,
 create_time datetime NOT NULL, INDEX idx_lease_file(lease_id,id)
);
