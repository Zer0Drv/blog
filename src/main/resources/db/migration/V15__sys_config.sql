CREATE TABLE sys_config (
    config_key VARCHAR(128) NOT NULL PRIMARY KEY,
    config_value TEXT NULL,
    value_type VARCHAR(16) NOT NULL,
    group_name VARCHAR(32) NOT NULL,
    description VARCHAR(512) NOT NULL,
    validation_rule TEXT NOT NULL,
    hot_reload BOOLEAN NOT NULL,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sys_config_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(128) NOT NULL,
    action VARCHAR(16) NOT NULL,
    old_value TEXT NOT NULL,
    new_value TEXT NOT NULL,
    old_source VARCHAR(16) NOT NULL,
    new_source VARCHAR(16) NOT NULL,
    operator_id BIGINT NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_sys_config_audit_time (create_time, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
