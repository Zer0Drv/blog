ALTER TABLE `user` ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE auth_refresh_session (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_version BIGINT NOT NULL,
    family VARCHAR(64) NOT NULL,
    access_jti VARCHAR(64) NOT NULL,
    expires_at BIGINT NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT FALSE,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX idx_refresh_user(user_id)
);

UPDATE sys_config SET config_value='900000' WHERE config_key='jwt.expiration-ms' AND CAST(config_value AS DECIMAL) > 900000;
