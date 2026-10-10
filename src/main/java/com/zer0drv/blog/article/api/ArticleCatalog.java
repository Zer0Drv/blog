package com.zer0drv.blog.article.api;

import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.common.response.PageResult;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 文章域跨模块只读目录（窄契约，issue #24 第二步）：comment / interaction /
 * social / seo / admin 读文章数据的唯一入口，替代原先经 {@code IService<Article>}
 * 泄漏的 MP CRUD 与直注 ArticleMapper。
 *
 * <p>可见性谓词的唯一出处是 {@code ArticleVisibility}（第一步已收编）；本接口的
 * 「Visible」族方法是它的转发，不新增过滤逻辑。本包不含任何 MyBatis-Plus 类型，
 * 外模块单测直接 mock 本接口即可，无需 MP 元数据初始化。
 *
 * @author Yoruhaki
 */
public interface ArticleCatalog {

    /**
     * 文章当前是否对外可见（PUBLISHED 且 publish_time 非空且到点且未删除）。
     * 不存在 / 已删除按不可见处理。评论前置校验与 {@link #findRef} 组合使用，
     * 以区分「不存在」与「未发布」两个错误码。
     */
    boolean isVisible(long articleId);

    /**
     * 取可见文章的引用投影；不存在 / 已删除 / 不可见一律抛 ARTICLE_NOT_EXIST
     * （互动前置校验语义：对外不暴露未发布内容的存在性）。
     */
    ArticleRef requireVisible(long articleId);

    /**
     * 按 id 查引用投影：未删除、任意状态（评论前置校验区分错误码、
     * 通知邮件取标题、管理端审核补发通知用）。
     */
    Optional<ArticleRef> findRef(Long id);

    /**
     * 按 id 集合批量查引用投影：未删除、任意状态（管理端评论列表文章标题联查用，
     * 语义同 {@link #findRef} 的批量形态）。
     */
    List<ArticleRef> listRefsByIds(Collection<Long> ids);

    /**
     * 可见文章按作者集合分页（publish_time 倒序，含作者 / 分类 / 标签装配）。
     * 关注流与作者主页文章列表用。authorIds 为空返回空页（不触库）。
     */
    PageResult<ArticleListVO> pageVisibleByAuthors(Collection<Long> authorIds, long page, long size);

    /**
     * 某作者的可见文章总数（作者主页 articleCount 用）。
     */
    long countVisibleByAuthor(long authorId);

    /**
     * 最新可见文章投影（publish_time 倒序，至多 limit 条）。SEO sitemap / RSS 专用。
     */
    List<ArticleFeedEntry> listVisibleLatest(int limit);

    /**
     * 按 id 集合取文章列表 VO（含装配），保持入参 id 顺序。
     * 注意：<b>不过滤可见性</b>，仅过滤已删除（找不到的 id 跳过）——
     * 收藏夹等「本人的清单」语义：已下架 / 回草稿的文章仍留在清单中，
     * 语义等价原 {@code listByIds + 内存过滤 + assemble}。ids 为空返回空表（不触库）。
     */
    List<ArticleListVO> listByIdsPreserveOrder(Collection<Long> ids);
}
