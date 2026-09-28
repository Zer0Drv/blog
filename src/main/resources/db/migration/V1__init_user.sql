-- 用户表（MySQL 8.4 / utf8mb4）
CREATE TABLE IF NOT EXISTS `user`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户id',
    `username`   VARCHAR(64)  NOT NULL COMMENT '用户名',
    `password`   VARCHAR(100) NOT NULL DEFAULT '' COMMENT '密码（BCrypt）',
    `email`      VARCHAR(128) NOT NULL DEFAULT '' COMMENT '邮箱',
    `nickname`   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '昵称',
    `avatar`     VARCHAR(512) NOT NULL DEFAULT '' COMMENT '头像URL',
    `bio`        VARCHAR(255) NOT NULL DEFAULT '' COMMENT '个人简介',
    `role`       VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色：ADMIN/AUTHOR/USER',
    `status`     TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0-正常；1-封禁',
    `github_id`  BIGINT       NULL COMMENT 'GitHub账号id（OAuth绑定）',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除；1-删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`),
    KEY `idx_github_id` (`github_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';
