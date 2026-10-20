CREATE TABLE user_totp (
    user_id BIGINT NOT NULL PRIMARY KEY,
    secret VARCHAR(256) NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    setup_expires BIGINT NOT NULL DEFAULT 0,
    last_step BIGINT NOT NULL DEFAULT -1,
    failures INT NOT NULL DEFAULT 0,
    blocked_until BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE totp_recovery (
    user_id BIGINT NOT NULL,
    code_hash CHAR(64) NOT NULL,
    PRIMARY KEY (user_id, code_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One outstanding challenge per user; random bearer is stored only as SHA-256.
CREATE TABLE totp_challenge (
    user_id BIGINT NOT NULL PRIMARY KEY,
    token_hash CHAR(64) NOT NULL UNIQUE,
    credential_hash CHAR(64) NOT NULL,
    expires_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
