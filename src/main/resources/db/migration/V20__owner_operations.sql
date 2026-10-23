CREATE TABLE owner_operation_lock (id INT PRIMARY KEY);
INSERT INTO owner_operation_lock(id) VALUES(1);
CREATE TABLE owner_operation_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator_id BIGINT NOT NULL,
    target_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
