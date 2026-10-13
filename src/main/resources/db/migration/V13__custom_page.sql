-- #18：独立页面，不进入文章列表、搜索、RSS、社交互动或文章审核流程。
CREATE TABLE `custom_page` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `slug` VARCHAR(200) NOT NULL,
  `title` VARCHAR(200) NOT NULL,
  `content` LONGTEXT NOT NULL,
  `editor_type` VARCHAR(16) NOT NULL DEFAULT 'MARKDOWN',
  `status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_custom_page_slug` (`slug`),
  KEY `idx_custom_page_status` (`status`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='独立自定义页面';

INSERT INTO `site_config` (`config_key`, `config_value`, `description`)
SELECT 'site.page_navigation', '[]', '自定义页面导航：按顺序配置 slug JSON 数组，仅已发布页面公开展示'
WHERE NOT EXISTS (SELECT 1 FROM `site_config` WHERE `config_key` = 'site.page_navigation');
