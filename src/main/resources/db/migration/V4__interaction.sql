-- M3 互动模块：浏览量 / 评论（两层楼中楼）/ 文章点赞 / 文章收藏 / 评论点赞（MySQL 8.4 / utf8mb4）
ALTER TABLE `article`
    ADD COLUMN `view_count` BIGINT NOT NULL DEFAULT 0 COMMENT '浏览量' AFTER `publish_time`;

CREATE TABLE IF NOT EXISTS `comment`
(
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '评论id',
    `article_id`       BIGINT       NOT NULL COMMENT '文章id',
    `user_id`          BIGINT       NOT NULL COMMENT '评论人id',
    `parent_id`        BIGINT       NOT NULL DEFAULT 0 COMMENT '0=主评论；否则为主评论id（严格两层）',
    `reply_to_user_id` BIGINT       NULL COMMENT '被回复人id（回复/@ 场景）',
    `content`          VARCHAR(1000) NOT NULL COMMENT '评论内容',
    `like_count`       INT          NOT NULL DEFAULT 0 COMMENT '点赞数',
    `status`           VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' COMMENT '状态：NORMAL/FOLDED（M5 审核用，本阶段只写 NORMAL）',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除；1-删除',
    PRIMARY KEY (`id`),
    KEY `idx_article_parent` (`article_id`, `parent_id`),
    KEY `idx_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '评论表';

CREATE TABLE IF NOT EXISTS `article_like`
(
    `id`          BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `article_id`  BIGINT NOT NULL COMMENT '文章id',
    `user_id`     BIGINT NOT NULL COMMENT '用户id',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`     TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除位（保留字段；取消点赞走物理删除）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_article_user` (`article_id`, `user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文章点赞关联表';

CREATE TABLE IF NOT EXISTS `article_favorite`
(
    `id`          BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `article_id`  BIGINT NOT NULL COMMENT '文章id',
    `user_id`     BIGINT NOT NULL COMMENT '用户id',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`     TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除位（保留字段；取消收藏走物理删除）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_article_user` (`article_id`, `user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文章收藏关联表';

CREATE TABLE IF NOT EXISTS `comment_like`
(
    `id`          BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `comment_id`  BIGINT NOT NULL COMMENT '评论id',
    `user_id`     BIGINT NOT NULL COMMENT '用户id',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`     TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除位（保留字段；取消点赞走物理删除）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_comment_user` (`comment_id`, `user_id`),
    KEY `idx_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '评论点赞关联表';