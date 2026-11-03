-- Administrator prepares additive V7 fields before deploying the new application.
-- Run only after checking that these columns/constraints do not exist.
ALTER TABLE norman_acceptance.service_profile
 ADD COLUMN commission_bps INT NOT NULL DEFAULT 2000,
 ADD CONSTRAINT ck_profile_commission CHECK (commission_bps BETWEEN 0 AND 10000);
ALTER TABLE norman_acceptance.club_order
 ADD COLUMN member_commission BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE norman_acceptance.order_member
 ADD COLUMN commission_bps INT NULL,
 ADD CONSTRAINT ck_member_commission CHECK (commission_bps IS NULL OR commission_bps BETWEEN 0 AND 10000);
