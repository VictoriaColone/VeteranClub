-- Run once as the database administrator before deploying the V8 application.
-- All existing users remain unsubmitted; no assumed adult/verified data is backfilled.
ALTER TABLE club_user ADD COLUMN real_name_cipher TEXT;
ALTER TABLE club_user ADD COLUMN id_number_cipher TEXT;
ALTER TABLE club_user ADD COLUMN id_number_hash VARCHAR(64);
ALTER TABLE club_user ADD COLUMN identity_submitted_at BIGINT;
ALTER TABLE club_user ADD COLUMN identity_consent_version VARCHAR(24);
ALTER TABLE club_user ADD COLUMN closed_at BIGINT;
ALTER TABLE club_user ADD COLUMN password_hash VARCHAR(200);
ALTER TABLE sms_code ADD COLUMN purpose VARCHAR(16) NOT NULL DEFAULT 'login';
CREATE UNIQUE INDEX uk_user_id_number ON club_user(id_number_hash);
