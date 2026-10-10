/**
 * 文章域对外契约包（端口）：外模块读文章数据的唯一入口（issue #24 第二步）。
 *
 * <p>约束：
 * <ul>
 *   <li>本包只允许出现接口与 record，禁止任何 MyBatis-Plus 类型——外模块单测
 *       只 mock {@link com.zer0drv.blog.article.api.ArticleCatalog}，不再需要
 *       TableInfoHelper 初始化样板；</li>
 *   <li>便利方法只能是「可见性谓词（ArticleVisibility）+ 装配」的转发，
 *       不得新增过滤逻辑；</li>
 *   <li>文章实体 / ArticleStatus 存储形态 / 逻辑删除 SQL 等实现细节留在
 *       article 内部包，不出现在本包签名中。</li>
 * </ul>
 */
package com.zer0drv.blog.article.api;
