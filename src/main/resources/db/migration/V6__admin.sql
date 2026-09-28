-- M5 管理后台：文章置顶/推荐位 + 敏感词库（MySQL 8.4 / utf8mb4）
ALTER TABLE `article`
    ADD COLUMN `is_top`         TINYINT NOT NULL DEFAULT 0 COMMENT '置顶：0-否；1-是' AFTER `view_count`,
    ADD COLUMN `is_recommended` TINYINT NOT NULL DEFAULT 0 COMMENT '推荐位：0-否；1-是' AFTER `is_top`;

CREATE TABLE IF NOT EXISTS `sensitive_word`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `word`        VARCHAR(64) NOT NULL COMMENT '敏感词',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除；1-删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_word` (`word`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '敏感词表';
