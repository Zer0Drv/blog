/**
 * 文章域对外契约包（端口）：外模块读文章数据的唯一入口（issue #24 第二步）。
 *
 * <p>约束：
 * <ul>
 *   <li>本包只允许出现接口、record 与契约枚举（ArticleStatus 于 issue #34 第六步
 *       自 article.enums 上移，外模块状态校验 / 写操作的契约类型；Visibility 于
 *       issue #15 新增，口令保护 / 私密可见性级别的契约枚举），禁止任何
 *       MyBatis-Plus 类型——外模块单测
 *       只 mock {@link com.zer0drv.blog.article.api.ArticleCatalog}，不再需要
 *       TableInfoHelper 初始化样板；</li>
 *   <li>{@link com.zer0drv.blog.article.api.ArticleCatalog} 的便利方法只能是
 *       「可见性谓词（ArticleVisibility）+ 装配」的转发，不得新增过滤逻辑；
 *       管理端统计口径（仅 status=PUBLISHED、无 publish_time 条件）与可见性谓词
 *       语义刻意分离，独立承载于
 *       {@link com.zer0drv.blog.article.api.ArticleStatsPort}（issue #34 第五步）；</li>
 *   <li>文章实体 / 逻辑删除 SQL 等实现细节留在
 *       article 内部包，不出现在本包签名中。</li>
 * </ul>
 */
package com.zer0drv.blog.article.api;
