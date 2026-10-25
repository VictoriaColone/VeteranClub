CREATE TABLE club_user (
 id VARCHAR(48) PRIMARY KEY, name VARCHAR(60) NOT NULL, disabled BOOLEAN NOT NULL DEFAULT FALSE, created_at BIGINT NOT NULL
);
CREATE TABLE login_identity (
 id VARCHAR(48) PRIMARY KEY, user_id VARCHAR(48) NOT NULL, provider VARCHAR(24) NOT NULL,
 app_id VARCHAR(80) NOT NULL DEFAULT '', subject_hash VARCHAR(64) NOT NULL, subject_cipher TEXT NOT NULL,
 masked_value VARCHAR(32) NOT NULL, created_at BIGINT NOT NULL,
 CONSTRAINT uk_identity UNIQUE(provider,app_id,subject_hash), FOREIGN KEY(user_id) REFERENCES club_user(id)
);
CREATE TABLE staff_account (
 id VARCHAR(48) PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE, name VARCHAR(60) NOT NULL,
 password_hash VARCHAR(200) NOT NULL, role VARCHAR(24) NOT NULL, disabled BOOLEAN NOT NULL DEFAULT FALSE,
 totp_cipher TEXT, last_totp_step BIGINT NOT NULL DEFAULT -1, created_at BIGINT NOT NULL,
 CONSTRAINT ck_staff_role CHECK(role IN ('admin','customer_service','finance'))
);
CREATE TABLE auth_session (
 token_hash VARCHAR(64) PRIMARY KEY, account_id VARCHAR(48) NOT NULL, audience VARCHAR(12) NOT NULL,
 csrf_hash VARCHAR(64) NOT NULL, bearer BOOLEAN NOT NULL, expires_at BIGINT NOT NULL, last_seen_at BIGINT NOT NULL, created_at BIGINT NOT NULL
);
CREATE INDEX ix_session_account ON auth_session(account_id,audience);
CREATE INDEX ix_session_expiry ON auth_session(expires_at);
CREATE TABLE sms_code (
 phone_hash VARCHAR(64) PRIMARY KEY, code_hash VARCHAR(64) NOT NULL, attempts INT NOT NULL DEFAULT 0,
 sent_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, consumed BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE auth_challenge (
 token_hash VARCHAR(64) PRIMARY KEY, staff_id VARCHAR(48) NOT NULL, setup_cipher TEXT,
 attempts INT NOT NULL DEFAULT 0, expires_at BIGINT NOT NULL, consumed BOOLEAN NOT NULL DEFAULT FALSE,
 FOREIGN KEY(staff_id) REFERENCES staff_account(id)
);
CREATE TABLE security_bucket (
 bucket_key VARCHAR(120) PRIMARY KEY, hits INT NOT NULL, window_end BIGINT NOT NULL
);
CREATE INDEX ix_bucket_expiry ON security_bucket(window_end);
CREATE TABLE ip_ban (
 ip VARCHAR(64) PRIMARY KEY, reason VARCHAR(200) NOT NULL, expires_at BIGINT NOT NULL,
 created_at BIGINT NOT NULL, actor VARCHAR(60) NOT NULL, revoked BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE service_profile (
 id VARCHAR(48) PRIMARY KEY, user_id VARCHAR(48) NOT NULL UNIQUE, name VARCHAR(60) NOT NULL,
 subtitle VARCHAR(120) NOT NULL, game_ids VARCHAR(500) NOT NULL, tags VARCHAR(500) NOT NULL,
 rating DECIMAL(4,2) NOT NULL DEFAULT 5, completed INT NOT NULL DEFAULT 0, price INT NOT NULL DEFAULT 6800,
 color VARCHAR(12) NOT NULL, avatar VARCHAR(12) NOT NULL, available BOOLEAN NOT NULL DEFAULT FALSE,
 approved BOOLEAN NOT NULL DEFAULT FALSE, bio VARCHAR(500) NOT NULL, created_at BIGINT NOT NULL,
 FOREIGN KEY(user_id) REFERENCES club_user(id), CONSTRAINT ck_profile_price CHECK(price BETWEEN 100 AND 100000)
);
CREATE TABLE service_qualification (
 player_id VARCHAR(48) NOT NULL, service_type VARCHAR(12) NOT NULL, game_id VARCHAR(24) NOT NULL,
 granted_at BIGINT NOT NULL, PRIMARY KEY(player_id,service_type,game_id), FOREIGN KEY(player_id) REFERENCES service_profile(id),
 CONSTRAINT ck_qualification_type CHECK(service_type IN ('hourly','escort'))
);
CREATE TABLE qualification_application (
 id VARCHAR(48) PRIMARY KEY, user_id VARCHAR(48) NOT NULL, player_id VARCHAR(48) NOT NULL, name VARCHAR(60) NOT NULL,
 service_type VARCHAR(12) NOT NULL, game_ids VARCHAR(500) NOT NULL, introduction VARCHAR(500) NOT NULL,
 status VARCHAR(16) NOT NULL, active_key VARCHAR(120) UNIQUE, score INT, review_note VARCHAR(500),
 created_at BIGINT NOT NULL, reviewed_at BIGINT, FOREIGN KEY(user_id) REFERENCES club_user(id),
 FOREIGN KEY(player_id) REFERENCES service_profile(id)
);
CREATE INDEX ix_application_user ON qualification_application(user_id,created_at);
CREATE TABLE product (
 id VARCHAR(48) PRIMARY KEY, user_id VARCHAR(48) NOT NULL, player_id VARCHAR(48) NOT NULL, seller_name VARCHAR(60) NOT NULL,
 game_id VARCHAR(24) NOT NULL, service_type VARCHAR(12) NOT NULL, title VARCHAR(40) NOT NULL,
 description VARCHAR(200) NOT NULL, price INT NOT NULL, task VARCHAR(200) NOT NULL, active BOOLEAN NOT NULL, created_at BIGINT NOT NULL,
 FOREIGN KEY(user_id) REFERENCES club_user(id), FOREIGN KEY(player_id) REFERENCES service_profile(id),
 CONSTRAINT ck_product_price CHECK(price BETWEEN 100 AND 100000), CONSTRAINT ck_product_type CHECK(service_type IN ('hourly','escort'))
);
CREATE INDEX ix_product_catalog ON product(active,game_id,service_type,created_at);
CREATE TABLE club_order (
 id VARCHAR(48) PRIMARY KEY, product_id VARCHAR(48), product_title VARCHAR(40), boss_id VARCHAR(48) NOT NULL, boss_name VARCHAR(60) NOT NULL,
 game_id VARCHAR(24) NOT NULL, game_name VARCHAR(40) NOT NULL, region VARCHAR(40) NOT NULL, service_type VARCHAR(12) NOT NULL,
 task VARCHAR(200) NOT NULL, task_completed BOOLEAN NOT NULL DEFAULT FALSE, preferred_player_id VARCHAR(48), preferred_player_name VARCHAR(60),
 player_id VARCHAR(48), player_name VARCHAR(60), unit_price INT NOT NULL, quantity INT NOT NULL, amount INT NOT NULL,
 commission_bps INT NOT NULL, earnings INT NOT NULL, status VARCHAR(16) NOT NULL, refund_status VARCHAR(16) NOT NULL DEFAULT 'none',
 refund_id VARCHAR(48), refund_reason VARCHAR(200), refund_amount INT, refund_review_note VARCHAR(500),
 note VARCHAR(200) NOT NULL, created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, paid_at BIGINT, started_at BIGINT, finished_at BIGINT,
 payment_id VARCHAR(48), settlement_id VARCHAR(48), version BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY(boss_id) REFERENCES club_user(id),
 CONSTRAINT ck_order_amount CHECK(amount>0 AND earnings>=0 AND earnings<=amount),
 CONSTRAINT ck_order_quantity CHECK(quantity BETWEEN 1 AND 8), CONSTRAINT ck_order_fee CHECK(commission_bps BETWEEN 0 AND 10000),
 CONSTRAINT ck_order_status CHECK(status IN ('unpaid','waiting','accepted','serving','confirming','completed','cancelled'))
);
CREATE INDEX ix_order_owner ON club_order(boss_id,created_at);
CREATE INDEX ix_order_hall ON club_order(status,service_type,game_id,created_at);
CREATE INDEX ix_order_invitation ON club_order(preferred_player_id,status);
CREATE INDEX ix_order_expiry ON club_order(status,expires_at);
CREATE TABLE order_member (
 order_id VARCHAR(48) NOT NULL, player_id VARCHAR(48) NOT NULL, name VARCHAR(60) NOT NULL, position INT NOT NULL, settlement_id VARCHAR(48),
 PRIMARY KEY(order_id,player_id), CONSTRAINT uk_member_position UNIQUE(order_id,position),
 FOREIGN KEY(order_id) REFERENCES club_order(id), FOREIGN KEY(player_id) REFERENCES service_profile(id)
);
CREATE INDEX ix_member_player ON order_member(player_id,order_id);
CREATE TABLE busy_slot (
 player_id VARCHAR(48) PRIMARY KEY, order_id VARCHAR(48) NOT NULL,
 FOREIGN KEY(player_id) REFERENCES service_profile(id), FOREIGN KEY(order_id) REFERENCES club_order(id)
);
CREATE TABLE order_event (
 id VARCHAR(48) PRIMARY KEY, order_id VARCHAR(48) NOT NULL, at BIGINT NOT NULL, actor VARCHAR(60) NOT NULL,
 label VARCHAR(120) NOT NULL, note VARCHAR(500), FOREIGN KEY(order_id) REFERENCES club_order(id)
);
CREATE INDEX ix_event_order ON order_event(order_id,at);
CREATE TABLE payment_attempt (
 id VARCHAR(48) PRIMARY KEY, order_id VARCHAR(48) NOT NULL, user_id VARCHAR(48) NOT NULL, channel VARCHAR(24) NOT NULL,
 request_key VARCHAR(80) NOT NULL, amount INT NOT NULL, currency VARCHAR(8) NOT NULL, status VARCHAR(24) NOT NULL,
 transaction_id VARCHAR(120) UNIQUE, merchant_id VARCHAR(120), client_params TEXT, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 CONSTRAINT uk_payment_request UNIQUE(user_id,request_key), FOREIGN KEY(order_id) REFERENCES club_order(id)
);
CREATE INDEX ix_payment_order ON payment_attempt(order_id,status);
CREATE TABLE payment_notification (
 event_key VARCHAR(160) PRIMARY KEY, payment_id VARCHAR(48) NOT NULL, received_at BIGINT NOT NULL,
 FOREIGN KEY(payment_id) REFERENCES payment_attempt(id)
);
CREATE TABLE refund_request (
 id VARCHAR(48) PRIMARY KEY, order_id VARCHAR(48) NOT NULL, payment_id VARCHAR(48) NOT NULL, amount INT NOT NULL,
 status VARCHAR(24) NOT NULL, reason VARCHAR(200) NOT NULL, review_note VARCHAR(500), created_at BIGINT NOT NULL, reviewed_at BIGINT,
 channel_refund_id VARCHAR(120) UNIQUE, FOREIGN KEY(order_id) REFERENCES club_order(id), FOREIGN KEY(payment_id) REFERENCES payment_attempt(id)
);
CREATE TABLE income_entry (
 id VARCHAR(48) PRIMARY KEY, order_id VARCHAR(48) NOT NULL, player_id VARCHAR(48) NOT NULL, player_name VARCHAR(60) NOT NULL,
 amount INT NOT NULL, status VARCHAR(16) NOT NULL, created_at BIGINT NOT NULL, paid_at BIGINT,
 CONSTRAINT uk_income_share UNIQUE(order_id,player_id), FOREIGN KEY(order_id) REFERENCES club_order(id),
 CONSTRAINT ck_income_amount CHECK(amount>=0)
);
CREATE TABLE payout (
 id VARCHAR(48) PRIMARY KEY, income_id VARCHAR(48) NOT NULL UNIQUE, amount INT NOT NULL, status VARCHAR(24) NOT NULL,
 channel_reference VARCHAR(120) UNIQUE, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 FOREIGN KEY(income_id) REFERENCES income_entry(id)
);
CREATE TABLE background_task (
 id VARCHAR(48) PRIMARY KEY, type VARCHAR(32) NOT NULL, aggregate_id VARCHAR(48) NOT NULL, state VARCHAR(16) NOT NULL,
 run_at BIGINT NOT NULL, lease_until BIGINT, attempts INT NOT NULL DEFAULT 0, last_error VARCHAR(200),
 CONSTRAINT uk_task_aggregate UNIQUE(type,aggregate_id)
);
CREATE INDEX ix_task_due ON background_task(state,run_at,lease_until);
CREATE TABLE audit_log (
 id VARCHAR(48) PRIMARY KEY, at BIGINT NOT NULL, actor VARCHAR(60) NOT NULL, action VARCHAR(80) NOT NULL,
 target VARCHAR(120) NOT NULL, detail VARCHAR(500) NOT NULL
);
CREATE INDEX ix_audit_date ON audit_log(at);
CREATE TABLE idempotency_request (
 owner_id VARCHAR(48) NOT NULL, request_key VARCHAR(80) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 order_id VARCHAR(48) NOT NULL, created_at BIGINT NOT NULL, PRIMARY KEY(owner_id,request_key), FOREIGN KEY(order_id) REFERENCES club_order(id)
);
