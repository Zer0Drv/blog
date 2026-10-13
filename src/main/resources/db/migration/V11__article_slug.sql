-- P1 文章 slug 固定链接（issue #14）：article 表新增 slug 列 + 唯一索引，存量回填
-- 序号说明：V10__friend_link.sql 位于 feat/friend-link 分支（已推送未合并），与本脚本无交集。
-- 若 friend-link 先合并，本脚本以 V11 顺延生效；若本 PR 先合并，friend-link 需把 V10 重号为
-- V11 之后的版本（Flyway 要求版本单调且不重复）。

-- slug 固定链接列。保留 NULL 能力：新建文章遇纯中文等清洗不出 ASCII 字母数字的标题时，
-- 服务端走「先插入取 id、同事务回写 post-{id}」的两步写入，事务窗口内 slug 短暂为空；
-- 此外全部写入路径（显式指定 / 标题生成 / id 兜底）都保证落库非空
ALTER TABLE `article`
    ADD COLUMN `slug` VARCHAR(200) NULL COMMENT '固定链接 slug（唯一；小写字母/数字/连字符）' AFTER `title`;

-- 存量回填：统一 post-{id} 兜底——简单可靠、字符集安全（不依赖标题语言，不引拼音库），
-- id 天然唯一保证零冲突；作者随后可在编辑器中改为语义化 slug
UPDATE `article` SET `slug` = CONCAT('post-', `id`) WHERE `slug` IS NULL;

-- 全局唯一索引：覆盖回收站（deleted=1）行——回收站文章仍占用 slug，
-- 避免外链被新文章抢占后恢复时冲突；彻底删除（物理删）才释放 slug
ALTER TABLE `article`
    ADD CONSTRAINT `uk_article_slug` UNIQUE (`slug`);
