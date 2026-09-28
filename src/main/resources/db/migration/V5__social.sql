-- M4 社交模块：关注 / 统一通知中心 / 一对一私信（v1 轮询）（MySQL 8.4 / utf8mb4）

CREATE TABLE IF NOT EXISTS `follow`
(
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `follower_id` BIGINT   NOT NULL COMMENT '关注人id',
    `followee_id` BIGINT   NOT NULL COMMENT '被关注人id',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_follow` (`follower_id`, `followee_id`),
    KEY `idx_followee` (`followee_id`)
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
    KEY `idx_user_read` (`user_id`, `read_flag`),
    KEY `idx_user_create` (`user_id`, `create_time`)
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
    KEY `idx_sender_receiver` (`sender_id`, `receiver_id`),
    KEY `idx_receiver_read` (`receiver_id`, `read_flag`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '私信表';
