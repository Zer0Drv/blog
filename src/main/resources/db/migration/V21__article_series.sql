-- Series are author-owned ordered collections. Deletion removes the collection, never its articles.
CREATE TABLE `article_series` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `owner_id` BIGINT NOT NULL,
    `title` VARCHAR(200) NOT NULL,
    `description` VARCHAR(2000) NOT NULL DEFAULT '',
    `cover` VARCHAR(512) NOT NULL DEFAULT '',
    `slug` VARCHAR(200) NOT NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    `revision` BIGINT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_article_series_slug` (`slug`),
    KEY `idx_article_series_owner` (`owner_id`, `update_time`, `id`),
    KEY `idx_article_series_status` (`status`, `update_time`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作者文章系列';

CREATE TABLE `article_series_item` (
    `series_id` BIGINT NOT NULL,
    `article_id` BIGINT NOT NULL,
    `position` INT NOT NULL,
    PRIMARY KEY (`series_id`, `article_id`),
    UNIQUE KEY `uk_series_position` (`series_id`, `position`),
    KEY `idx_series_item_article` (`article_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系列文章顺序';
