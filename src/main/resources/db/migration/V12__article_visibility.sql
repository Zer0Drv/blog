-- P1 密码保护 / 私密文章（issue #15）：article 表新增可见性列与访问口令哈希列。
-- 数据模型决策：可见性独立于 status 生命周期（DRAFT/PUBLISHED/OFFLINE + deleted 回收站），
-- 避免与发布状态、定时发布、回收站状态纠缠成组合爆炸；visibility 仅在「已发布且到点」时
-- 参与公众可见性判定（谓词单一出处 ArticleVisibility，详见该类 javadoc）。

-- 可见性列：PUBLIC-公开（默认，存量回填即公开，行为不变）；PASSWORD-口令保护
-- （不进任何公众列表/Feed/归档/搜索/RSS/sitemap，详情需口令）；PRIVATE-私密
-- （仅作者/ADMIN 可见，公众端与「不存在」同语义）
ALTER TABLE `article`
    ADD COLUMN `visibility` VARCHAR(16) NOT NULL DEFAULT 'PUBLIC' COMMENT '可见性：PUBLIC-公开；PASSWORD-口令保护；PRIVATE-仅作者/管理员' AFTER `status`;

-- 访问口令哈希：仅 visibility=PASSWORD 时有值；存 BCrypt 哈希（复用账号密码同款
-- PasswordEncoder），明文口令永不出库；切回 PUBLIC/PRIVATE 时由服务层清空
ALTER TABLE `article`
    ADD COLUMN `access_password_hash` VARCHAR(100) NULL COMMENT '口令保护访问口令（BCrypt 哈希；仅 visibility=PASSWORD 时有值）' AFTER `visibility`;
