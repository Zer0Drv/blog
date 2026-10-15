CREATE TABLE attachment_cleanup_lock (
    id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO attachment_cleanup_lock(id) VALUES (1);

CREATE TABLE attachment_cleanup (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    storage VARCHAR(16) NOT NULL,
    object_key VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    fingerprint VARCHAR(255) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'OBSERVING',
    marked_at DATETIME NOT NULL,
    eligible_at DATETIME NOT NULL,
    completed_at DATETIME NULL,
    reclaimed_bytes BIGINT NOT NULL DEFAULT 0,
    operator_id BIGINT NOT NULL,
    last_error VARCHAR(255) NOT NULL DEFAULT '',
    UNIQUE KEY uk_attachment_cleanup_object(storage, object_key),
    KEY idx_attachment_cleanup_state(state, eligible_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
