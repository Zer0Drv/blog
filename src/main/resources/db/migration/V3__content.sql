-- 内容模块：分类 / 标签 / 文章 / 文章-标签关联（MySQL 8.4 / utf8mb4）
CREATE TABLE IF NOT EXISTS `category`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '分类id',
    `name`        VARCHAR(64) NOT NULL COMMENT '分类名',
    `parent_id`   BIGINT      NOT NULL DEFAULT 0 COMMENT '父分类id：0=根分类',
    `sort`        INT         NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除；1-删除',
    PRIMARY KEY (`id`),
    KEY `idx_parent_id` (`parent_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文章分类表';

CREATE TABLE IF NOT EXISTS `tag`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '标签id',
    `name`        VARCHAR(64) NOT NULL COMMENT '标签名',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除；1-删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文章标签表';

CREATE TABLE IF NOT EXISTS `article`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '文章id',
    `title`        VARCHAR(200) NOT NULL COMMENT '标题',
    `summary`      VARCHAR(500) NOT NULL DEFAULT '' COMMENT '摘要',
    `content`      LONGTEXT     NOT NULL COMMENT '正文（Markdown 或富文本 HTML）',
    `editor_type`  VARCHAR(16)  NOT NULL COMMENT '编辑器类型：MARKDOWN/RICHTEXT',
    `cover`        VARCHAR(512) NOT NULL DEFAULT '' COMMENT '封面图URL',
    `category_id`  BIGINT       NULL COMMENT '分类id',
    `author_id`    BIGINT       NOT NULL COMMENT '作者id',
    `status`       VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/PUBLISHED/OFFLINE',
    `publish_time` DATETIME     NULL COMMENT '首次发布时间',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除；1-删除',
    PRIMARY KEY (`id`),
    KEY `idx_status_publish` (`status`, `publish_time`),
    KEY `idx_author` (`author_id`),
    KEY `idx_category` (`category_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文章表';

CREATE TABLE IF NOT EXISTS `article_tag`
(
    `id`         BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `article_id` BIGINT NOT NULL COMMENT '文章id',
    `tag_id`     BIGINT NOT NULL COMMENT '标签id',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_article_tag` (`article_id`, `tag_id`),
    KEY `idx_tag_id` (`tag_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文章-标签关联表';