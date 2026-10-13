-- P1：友情链接（issue #17）
-- 对标 WordPress Links / Halo 友链插件：名称/URL/Logo/描述/排序/启停状态
CREATE TABLE `friend_link` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(64) NOT NULL COMMENT '站点名称',
  `url` VARCHAR(512) NOT NULL COMMENT '链接地址',
  `logo` VARCHAR(512) NOT NULL DEFAULT '' COMMENT 'Logo 图片 URL',
  `description` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '一句话描述',
  `sort` INT NOT NULL DEFAULT 0 COMMENT '排序值（升序，越小越靠前）',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1-启用 0-停用',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_friend_link_status_sort` (`status`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='友情链接表';

-- 种子：展示位开关（与 site_config 联动；true-公众端页脚/友链页展示，幂等，参照 V9 写法）
INSERT INTO `site_config` (`config_key`, `config_value`, `description`)
SELECT 'site.friend_links_enabled', 'true', '友情链接展示开关：true-公众端展示已启用友链'
WHERE NOT EXISTS (SELECT 1 FROM `site_config` WHERE `config_key` = 'site.friend_links_enabled');
