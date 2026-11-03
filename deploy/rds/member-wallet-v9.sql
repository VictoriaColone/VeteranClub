-- Run once as schema administrator before deploying V9. No data backfill.
CREATE TABLE member_bank_account (
 player_id VARCHAR(48) PRIMARY KEY,
 holder_cipher VARCHAR(1024) NOT NULL,
 number_cipher VARCHAR(1024) NOT NULL,
 bank_name VARCHAR(80) NOT NULL,
 last_four VARCHAR(4) NOT NULL,
 updated_at BIGINT NOT NULL
);
CREATE TABLE wallet_income (
 income_id VARCHAR(48) PRIMARY KEY,
 order_id VARCHAR(48) NOT NULL,
 player_id VARCHAR(48) NOT NULL,
 fund_mode VARCHAR(16) NOT NULL,
 amount BIGINT NOT NULL,
 frozen BIGINT NOT NULL DEFAULT 0,
 paid BIGINT NOT NULL DEFAULT 0,
 release_at BIGINT NOT NULL,
 blocked BOOLEAN NOT NULL DEFAULT FALSE,
 CHECK (amount >= 0 AND frozen >= 0 AND paid >= 0 AND frozen + paid <= amount)
);
CREATE INDEX ix_wallet_player ON wallet_income(player_id, fund_mode, release_at);
CREATE INDEX ix_wallet_order ON wallet_income(order_id);
CREATE TABLE withdrawal_request (
 id VARCHAR(48) PRIMARY KEY,
 user_id VARCHAR(48) NOT NULL,
 player_id VARCHAR(48) NOT NULL,
 player_name VARCHAR(80) NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 amount BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL,
 holder_cipher VARCHAR(1024) NOT NULL,
 number_cipher VARCHAR(1024) NOT NULL,
 bank_name VARCHAR(80) NOT NULL,
 last_four VARCHAR(4) NOT NULL,
 review_note VARCHAR(500),
 payment_reference VARCHAR(100),
 payment_note VARCHAR(500),
 created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL,
 paid_at BIGINT,
 UNIQUE (user_id, request_key),
 UNIQUE (payment_reference),
 CHECK (amount > 0)
);
CREATE INDEX ix_withdrawal_player ON withdrawal_request(player_id, created_at);
CREATE INDEX ix_withdrawal_status ON withdrawal_request(status, created_at);
CREATE TABLE withdrawal_allocation (
 withdrawal_id VARCHAR(48) NOT NULL,
 income_id VARCHAR(48) NOT NULL,
 amount BIGINT NOT NULL,
 PRIMARY KEY (withdrawal_id, income_id),
 CHECK (amount > 0)
);
CREATE TABLE wallet_entry (
 id VARCHAR(48) PRIMARY KEY,
 player_id VARCHAR(48) NOT NULL,
 fund_mode VARCHAR(16) NOT NULL,
 kind VARCHAR(24) NOT NULL,
 reference_id VARCHAR(48) NOT NULL,
 amount BIGINT NOT NULL,
 created_at BIGINT NOT NULL,
 UNIQUE (kind, reference_id)
);
CREATE INDEX ix_wallet_entry_player ON wallet_entry(player_id, created_at);
CREATE TABLE wechat_payment_identity (
 user_id VARCHAR(48) NOT NULL,
 app_id VARCHAR(32) NOT NULL,
 openid_cipher VARCHAR(1024) NOT NULL,
 created_at BIGINT NOT NULL,
 PRIMARY KEY (user_id, app_id)
);
CREATE TABLE wechat_oauth_state (
 state_hash VARCHAR(64) PRIMARY KEY,
 user_id VARCHAR(48) NOT NULL,
 session_hash VARCHAR(64) NOT NULL,
 order_id VARCHAR(48) NOT NULL,
 expires_at BIGINT NOT NULL,
 consumed BOOLEAN NOT NULL DEFAULT FALSE
);
