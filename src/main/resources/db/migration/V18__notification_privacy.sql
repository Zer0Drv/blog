CREATE TABLE comment_notification_removal (
    comment_id BIGINT PRIMARY KEY,
    reason VARCHAR(32) NOT NULL,
    removed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE notification_retraction_audit (
    notification_id BIGINT PRIMARY KEY,
    operator_id BIGINT NOT NULL,
    retracted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- Content snapshots cannot track subsequent deletion/visibility changes. Remove historical copies.
UPDATE notification SET summary='' WHERE comment_id IS NOT NULL;
