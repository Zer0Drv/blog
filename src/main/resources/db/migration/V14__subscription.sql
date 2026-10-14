ALTER TABLE `user`
 ADD COLUMN article_notify_enabled TINYINT NOT NULL DEFAULT 0,
 ADD COLUMN article_email_notify_enabled TINYINT NOT NULL DEFAULT 0,
 ADD COLUMN article_subscribed_at DATETIME NULL,
 ADD COLUMN article_unsubscribe_token VARCHAR(64) NOT NULL DEFAULT '';

CREATE TABLE subscription_publication (
 article_id BIGINT NOT NULL PRIMARY KEY,
 due_at DATETIME NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 lease_until DATETIME NULL,
 lease_token VARCHAR(32) NOT NULL DEFAULT '',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_subscription_pending (state, due_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE subscription_delivery (
 article_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 mail_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 mail_sent_at DATETIME NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(article_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE subscription_mail_budget (
 user_id BIGINT NOT NULL PRIMARY KEY,
 window_started DATETIME NOT NULL,
 attempts INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 上线前已公开的文章标记完成；存量定时文章仍排队。
INSERT INTO subscription_publication(article_id, due_at, state)
SELECT id, publish_time, CASE WHEN publish_time<=NOW() THEN 'DONE' ELSE 'PENDING' END FROM article
WHERE status='PUBLISHED' AND visibility='PUBLIC' AND publish_time IS NOT NULL AND deleted=0;
