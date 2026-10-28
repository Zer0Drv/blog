-- article remains the effective publication; only an explicit publish replaces it.
ALTER TABLE article ADD COLUMN edit_revision BIGINT NOT NULL DEFAULT 0;
CREATE TABLE article_working_copy (
    article_id BIGINT NOT NULL PRIMARY KEY,
    title VARCHAR(200) NOT NULL DEFAULT '',
    slug VARCHAR(200) NOT NULL,
    summary VARCHAR(500) NOT NULL DEFAULT '',
    content MEDIUMTEXT NOT NULL,
    editor_type VARCHAR(16) NOT NULL,
    cover VARCHAR(512) NOT NULL DEFAULT '',
    category_id BIGINT NULL,
    tag_ids TEXT NOT NULL,
    visibility VARCHAR(16) NOT NULL,
    access_password_hash VARCHAR(100) NULL,
    publish_time DATETIME NULL,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持久文章工作稿；仅作者和管理员可读取';
