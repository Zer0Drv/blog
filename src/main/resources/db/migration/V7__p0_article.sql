-- P0 article 系：文章版本快照表 + 全文搜索（content_text 纯文本列 + ngram FULLTEXT）
CREATE TABLE IF NOT EXISTS `article_version`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `article_id`  BIGINT       NOT NULL COMMENT '文章id',
    `version`     INT          NOT NULL COMMENT '版本号（自 1 递增）',
    `title`       VARCHAR(200) NOT NULL,
    `summary`     VARCHAR(500) NOT NULL DEFAULT '',
    `content`     LONGTEXT     NOT NULL,
    `editor_type` VARCHAR(16)  NOT NULL,
    `cover`       VARCHAR(512) NOT NULL DEFAULT '',
    `category_id` BIGINT       NULL,
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted`     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_article_version` UNIQUE (`article_id`, `version`),
    KEY `idx_article_version_article` (`article_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文章版本快照表';

-- 正文纯文本冗余列（全文搜索用），历史数据以原始正文回填
ALTER TABLE `article`
    ADD COLUMN `content_text` LONGTEXT NULL COMMENT '正文纯文本（全文搜索用）' AFTER `content`;
UPDATE `article` SET `content_text` = `content`;
ALTER TABLE `article`
    ADD FULLTEXT KEY `ft_article_search` (`title`, `content_text`) WITH PARSER ngram;
