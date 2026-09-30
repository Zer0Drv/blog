-- P0 infra 系：站点设置中心 + 附件库
-- 站点配置表（公开接口只出白名单键，admin 读写全量）
CREATE TABLE `site_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `config_key` VARCHAR(64) NOT NULL COMMENT '配置键',
  `config_value` VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '配置值',
  `description` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '说明',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  CONSTRAINT `uk_site_config_key` UNIQUE (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站点配置表';

-- 种子：7 个已知配置键（幂等，参照 V2 写法）
INSERT INTO `site_config` (`config_key`, `config_value`, `description`)
SELECT 'site.name', 'Blog', '站点名称'
WHERE NOT EXISTS (SELECT 1 FROM `site_config` WHERE `config_key` = 'site.name');
INSERT INTO `site_config` (`config_key`, `config_value`, `description`)
SELECT 'site.description', '记录与分享', '站点描述'
WHERE NOT EXISTS (SELECT 1 FROM `site_config` WHERE `config_key` = 'site.description');
INSERT INTO `site_config` (`config_key`, `config_value`, `description`)
SELECT 'site.logo', '', '站点 Logo URL'
WHERE NOT EXISTS (SELECT 1 FROM `site_config` WHERE `config_key` = 'site.logo');
INSERT INTO `site_config` (`config_key`, `config_value`, `description`)
SELECT 'site.icp', '', 'ICP 备案号'
WHERE NOT EXISTS (SELECT 1 FROM `site_config` WHERE `config_key` = 'site.icp');
INSERT INTO `site_config` (`config_key`, `config_value`, `description`)
SELECT 'site.footer', '', '页脚自定义内容'
WHERE NOT EXISTS (SELECT 1 FROM `site_config` WHERE `config_key` = 'site.footer');
INSERT INTO `site_config` (`config_key`, `config_value`, `description`)
SELECT 'site.base_url', 'http://localhost:5173', '前端站点对外地址（RSS/邮件链接拼接用，不对外公开）'
WHERE NOT EXISTS (SELECT 1 FROM `site_config` WHERE `config_key` = 'site.base_url');
INSERT INTO `site_config` (`config_key`, `config_value`, `description`)
SELECT 'comment.review_required', 'false', '评论审核开关：true-发表评论先进入待审核队列'
WHERE NOT EXISTS (SELECT 1 FROM `site_config` WHERE `config_key` = 'comment.review_required');

-- 附件分组表（按用户隔离）
CREATE TABLE `attachment_group` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL COMMENT '属主用户id',
  `name` VARCHAR(64) NOT NULL COMMENT '分组名',
  `sort` INT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_attachment_group_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='附件分组表';

-- 附件表（上传即落库；删除只逻辑删记录，存储对象由 P1 物理清理）
CREATE TABLE `attachment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL COMMENT '上传者id',
  `group_id` BIGINT NULL COMMENT '分组id（NULL=未分组）',
  `url` VARCHAR(512) NOT NULL COMMENT '访问URL',
  `object_key` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '存储对象名（yyyyMM/uuid.ext）',
  `storage` VARCHAR(16) NOT NULL DEFAULT 'LOCAL' COMMENT '存储：MINIO/LOCAL',
  `filename` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '原始文件名',
  `media_type` VARCHAR(64) NOT NULL DEFAULT '',
  `size_bytes` BIGINT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_attachment_user` (`user_id`),
  KEY `idx_attachment_group` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='附件表';
