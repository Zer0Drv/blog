ALTER TABLE auth_refresh_session
    ADD COLUMN device_label VARCHAR(64) NULL,
    ADD COLUMN last_active_at BIGINT NULL,
    ADD INDEX idx_refresh_user_family (user_id, family),
    ADD INDEX idx_refresh_access_jti (access_jti);
