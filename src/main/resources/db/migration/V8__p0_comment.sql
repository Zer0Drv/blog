-- P0 §2.3 通知偏好：用户级评论邮件通知开关（0-关；1-开），存量行默认开
ALTER TABLE `user`
    ADD COLUMN `email_notify_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '评论邮件通知开关：0-关；1-开' AFTER `github_id`;
