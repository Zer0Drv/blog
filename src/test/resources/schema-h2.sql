-- 测试库（H2 MySQL 兼容模式）建表脚本：列与 db/migration/V1~V6 迁移后的最终结构一一对齐。
-- 不直接跑生产 migration 的原因：H2 约束名 schema 级全局唯一（MySQL 为表级），
-- 生产脚本中 article_like/article_favorite 的 UNIQUE KEY 同名 `uk_article_user` 冲突；
-- 此处约束名统一加表名前缀。ENGINE/CHARSET 表选项在 MySQL 模式下被 H2 忽略，保留以对齐。

-- ========== V1 用户表 ==========
CREATE TABLE IF NOT EXISTS `user`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户id',
    `username`    VARCHAR(64)  NOT NULL COMMENT '用户名',
    `password`    VARCHAR(100) NOT NULL DEFAULT '' COMMENT '密码（BCrypt）',
    `email`       VARCHAR(128) NOT NULL DEFAULT '' COMMENT '邮箱',
    `nickname`    VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '昵称',
    `avatar`      VARCHAR(512) NOT NULL DEFAULT '' COMMENT '头像URL',
    `bio`         VARCHAR(255) NOT NULL DEFAULT '' COMMENT '个人简介',
    `role`        VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色：ADMIN/AUTHOR/USER',
    `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0-正常；1-封禁',
    `github_id`   BIGINT       NULL COMMENT 'GitHub账号id（OAuth绑定）',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除；1-删除',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_user_username` UNIQUE (`username`),
    CONSTRAINT `uk_user_email` UNIQUE (`email`),
    KEY `idx_user_github_id` (`github_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';

-- V2 种子：博主账号 admin / admin123
INSERT INTO `user` (`username`, `password`, `email`, `nickname`, `role`, `status`)
SELECT 'admin', '$2a$10$ubGNSIh9Rg5Nl49HPoOD6ecRzSTnf6TgNBBPaN2uEpPS2eoPB2hXS', 'admin@blog.local', '博主', 'ADMIN', 0
WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE `username` = 'admin');

-- ========== V3 内容模块 ==========
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
    KEY `idx_category_parent_id` (`parent_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文章分类表';

CREATE TABLE IF NOT EXISTS `tag`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '标签id',
    `name`        VARCHAR(64) NOT NULL COMMENT '标签名',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除；1-删除',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_tag_name` UNIQUE (`name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文章标签表';

-- article 含 V4（view_count）与 V6（is_top / is_recommended）追加列
CREATE TABLE IF NOT EXISTS `article`
(
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '文章id',
    `title`          VARCHAR(200) NOT NULL COMMENT '标题',
    `summary`        VARCHAR(500) NOT NULL DEFAULT '' COMMENT '摘要',
    `content`        LONGTEXT     NOT NULL COMMENT '正文（Markdown 或富文本 HTML）',
    `editor_type`    VARCHAR(16)  NOT NULL COMMENT '编辑器类型：MARKDOWN/RICHTEXT',
    `cover`          VARCHAR(512) NOT NULL DEFAULT '' COMMENT '封面图URL',
    `category_id`    BIGINT       NULL COMMENT '分类id',
    `author_id`      BIGINT       NOT NULL COMMENT '作者id',
    `status`         VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/PUBLISHED/OFFLINE',
    `publish_time`   DATETIME     NULL COMMENT '首次发布时间',
    `view_count`     BIGINT       NOT NULL DEFAULT 0 COMMENT '浏览量',
    `is_top`         TINYINT      NOT NULL DEFAULT 0 COMMENT '置顶：0-否；1-是',
    `is_recommended` TINYINT      NOT NULL DEFAULT 0 COMMENT '推荐位：0-否；1-是',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除；1-删除',
    PRIMARY KEY (`id`),
    KEY `idx_article_status_publish` (`status`, `publish_time`),
    KEY `idx_article_author` (`author_id`),
    KEY `idx_article_category` (`category_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文章表';

CREATE TABLE IF NOT EXISTS `article_tag`
(
    `id`         BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `article_id` BIGINT NOT NULL COMMENT '文章id',
    `tag_id`     BIGINT NOT NULL COMMENT '标签id',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_article_tag` UNIQUE (`article_id`, `tag_id`),
    KEY `idx_article_tag_tag_id` (`tag_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文章-标签关联表';

-- ========== V4 互动模块 ==========
CREATE TABLE IF NOT EXISTS `comment`
(
    `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '评论id',
    `article_id`       BIGINT        NOT NULL COMMENT '文章id',
    `user_id`          BIGINT        NOT NULL COMMENT '评论人id',
    `parent_id`        BIGINT        NOT NULL DEFAULT 0 COMMENT '0=主评论；否则为主评论id（严格两层）',
    `reply_to_user_id` BIGINT        NULL COMMENT '被回复人id（回复/@ 场景）',
    `content`          VARCHAR(1000) NOT NULL COMMENT '评论内容',
    `like_count`       INT           NOT NULL DEFAULT 0 COMMENT '点赞数',
    `status`           VARCHAR(16)   NOT NULL DEFAULT 'NORMAL' COMMENT '状态：NORMAL/FOLDED',
    `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除；1-删除',
    PRIMARY KEY (`id`),
    KEY `idx_comment_article_parent` (`article_id`, `parent_id`),
    KEY `idx_comment_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '评论表';

CREATE TABLE IF NOT EXISTS `article_like`
(
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `article_id`  BIGINT   NOT NULL COMMENT '文章id',
    `user_id`     BIGINT   NOT NULL COMMENT '用户id',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`     TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除位（保留字段；取消点赞走物理删除）',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_article_like_article_user` UNIQUE (`article_id`, `user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文章点赞关联表';

CREATE TABLE IF NOT EXISTS `article_favorite`
(
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `article_id`  BIGINT   NOT NULL COMMENT '文章id',
    `user_id`     BIGINT   NOT NULL COMMENT '用户id',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`     TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除位（保留字段；取消收藏走物理删除）',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_article_favorite_article_user` UNIQUE (`article_id`, `user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文章收藏关联表';

CREATE TABLE IF NOT EXISTS `comment_like`
(
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `comment_id`  BIGINT   NOT NULL COMMENT '评论id',
    `user_id`     BIGINT   NOT NULL COMMENT '用户id',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`     TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除位（保留字段；取消点赞走物理删除）',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_comment_like_comment_user` UNIQUE (`comment_id`, `user_id`),
    KEY `idx_comment_like_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '评论点赞关联表';

-- ========== V5 社交模块 ==========
CREATE TABLE IF NOT EXISTS `follow`
(
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `follower_id` BIGINT   NOT NULL COMMENT '关注人id',
    `followee_id` BIGINT   NOT NULL COMMENT '被关注人id',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_follow_follower_followee` UNIQUE (`follower_id`, `followee_id`),
    KEY `idx_follow_followee` (`followee_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '关注关系表';

CREATE TABLE IF NOT EXISTS `notification`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NOT NULL COMMENT '接收者id',
    `type`        VARCHAR(32)  NOT NULL COMMENT '类型：COMMENT_REPLY/MENTION/ARTICLE_LIKE/FOLLOW/PRIVATE_MESSAGE/SYSTEM',
    `actor_id`    BIGINT       NULL COMMENT '触发人id（系统通知为空）',
    `article_id`  BIGINT       NULL COMMENT '关联文章id',
    `comment_id`  BIGINT       NULL COMMENT '关联评论id',
    `summary`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '摘要（如评论片段/文章标题）',
    `read_flag`   TINYINT      NOT NULL DEFAULT 0 COMMENT '已读：0-未读；1-已读',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除；1-删除',
    PRIMARY KEY (`id`),
    KEY `idx_notification_user_read` (`user_id`, `read_flag`),
    KEY `idx_notification_user_create` (`user_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '通知表';

CREATE TABLE IF NOT EXISTS `private_message`
(
    `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `sender_id`   BIGINT        NOT NULL COMMENT '发送人id',
    `receiver_id` BIGINT        NOT NULL COMMENT '接收人id',
    `content`     VARCHAR(1000) NOT NULL COMMENT '消息内容',
    `read_flag`   TINYINT       NOT NULL DEFAULT 0 COMMENT '已读：0-未读；1-已读',
    `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除；1-删除',
    PRIMARY KEY (`id`),
    KEY `idx_message_sender_receiver` (`sender_id`, `receiver_id`),
    KEY `idx_message_receiver_read` (`receiver_id`, `read_flag`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '私信表';

-- ========== V6 管理后台 ==========
CREATE TABLE IF NOT EXISTS `sensitive_word`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `word`        VARCHAR(64) NOT NULL COMMENT '敏感词',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除；1-删除',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_sensitive_word_word` UNIQUE (`word`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '敏感词表';
