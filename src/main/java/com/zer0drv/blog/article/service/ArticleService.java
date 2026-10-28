package com.zer0drv.blog.article.service;

import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.dto.ArticleSaveDTO;
import com.zer0drv.blog.article.dto.ArticleStatusDTO;
import com.zer0drv.blog.article.dto.AutosaveDTO;
import com.zer0drv.blog.article.vo.ArchiveMonthVO;
import com.zer0drv.blog.article.vo.ArticleAccessVO;
import com.zer0drv.blog.article.vo.ArticleDetailVO;
import com.zer0drv.blog.article.vo.ArticleEditingVO;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.article.vo.ArticleSearchResultVO;
import com.zer0drv.blog.article.vo.ArticleVersionDetailVO;
import com.zer0drv.blog.article.vo.ArticleVersionVO;
import com.zer0drv.blog.common.response.PageResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;
import java.time.LocalDate;

/**
 * 文章域作者 / 管理员用例接口（issue #24 第二步起不再 extends IService，
 * MP CRUD 收回模块内部；跨模块只读走 article.api.ArticleCatalog）。
 *
 * @author Yoruhaki
 */
public interface ArticleService {

    /**
     * 已发布文章分页列表（按发布时间倒序，支持关键字 / 标签 / 分类过滤）
     */
    PageResult<ArticleListVO> pagePublished(long page, long size, String keyword, Long tagId, Long categoryId);

    /**
     * 文章详情。匿名 / 非作者仅可见 PUBLISHED；作者本人或 ADMIN 可看任意状态。
     * jwt 可为 null（匿名访问）。
     * <p>口令保护（issue #15）：已到点的 PASSWORD 文章对非作者返回「需口令」极简视图
     * （passwordRequired=true，不含标题 / 正文）；持有效访问令牌（accessToken，口令校验
     * 端点签发）则按正常详情放行。PRIVATE 对公众与「不存在」同语义。
     */
    ArticleDetailVO getDetail(Long id, Jwt jwt, String accessToken);

    /**
     * 按 slug 取文章详情（issue #14 固定链接）。可见性、浏览量自增、存在性隐藏
     * 规则与 {@link #getDetail} 完全一致（含口令门禁）；slug 不存在或在回收站一律报
     * ARTICLE_NOT_EXIST。
     */
    ArticleDetailVO getDetailBySlug(String slug, Jwt jwt, String accessToken);

    /**
     * 校验口令保护文章的访问口令（issue #15）：通过则签发短期访问令牌（Redis，TTL 2h）。
     * 非「已到点的 PASSWORD 文章」一律报 ARTICLE_NOT_EXIST（不暴露存在性）；
     * 口令错误报 ARTICLE_PASSWORD_INVALID。限流编排在控制器层（CaptchaGuard 双维度计数）。
     */
    ArticleAccessVO verifyAccessPassword(Long id, String password);

    /**
     * 新建文章（允许直接发布），返回文章id
     */
    Long create(ArticleSaveDTO dto, Jwt jwt);

    /**
     * 编辑文章（仅本人或 ADMIN）
     */
    ArticleEditingVO update(Long id, ArticleSaveDTO dto, Jwt jwt);
    ArticleEditingVO getEditing(Long id, Jwt jwt);
    ArticleEditingVO publish(Long id, ArticleSaveDTO dto, Jwt jwt);
    ArticleEditingVO discardWorkingCopy(Long id, Long editRevision, Jwt jwt);

    /**
     * 删除文章（仅本人或 ADMIN，逻辑删除入回收站；P0 起不再级联清关联，保证可恢复）
     */
    void delete(Long id, Jwt jwt);

    /**
     * 从回收站恢复文章（仅本人或 ADMIN；回到草稿态，不直接上线）
     */
    void restore(Long id, Jwt jwt);

    /**
     * 彻底删除文章（仅本人或 ADMIN；物理删除本行并级联物理删标签关联 / 评论 /
     * 点赞收藏 / 全部版本快照，清除自动保存草稿，不可恢复）
     */
    void forceDelete(Long id, Jwt jwt);

    /**
     * 上架 / 下架 / 回草稿（仅本人或 ADMIN；首次发布写 publish_time）
     */
    void updateStatus(Long id, ArticleStatusDTO dto, Jwt jwt);

    /**
     * 本人文章分页（含草稿 / 下架，status 可空）
     */
    PageResult<ArticleListVO> pageMine(long page, long size, String status, Jwt jwt);

    /**
     * 批量组装文章列表 VO（作者 / 分类名 / 标签 内存联查，供我的收藏等场景复用）
     */
    List<ArticleListVO> assemble(List<Article> articles);

    /**
     * 文章版本列表（仅本人或 ADMIN，version 倒序，不含正文）
     */
    List<ArticleVersionVO> listVersions(Long id, Jwt jwt);

    /**
     * 文章版本详情（仅本人或 ADMIN；版本不存在抛 ARTICLE_VERSION_NOT_EXIST）
     */
    ArticleVersionDetailVO getVersion(Long id, Integer version, Jwt jwt);

    /**
     * 恢复到指定版本（仅本人或 ADMIN）：覆盖标题 / 摘要 / 正文 / 编辑器类型 / 封面 / 分类，
     * status 与 publishTime 不动；已发布文章仅写工作稿，正式更新时才快照旧生效稿。
     */
    ArticleEditingVO restoreVersion(Long id, Integer version, Long editRevision, Jwt jwt);

    /**
     * 自动保存持久工作稿（仅本人或 ADMIN）：校验 revision，允许空标题/正文，不产生版本快照。
     */
    ArticleEditingVO saveAutosave(Long id, AutosaveDTO dto, Jwt jwt);

    /**
     * 读取旧版 Redis 自动保存（仅本人或 ADMIN）：显式迁移来源，不存在返回 {exists:false}
     */
    Map<String, Object> getAutosave(Long id, Jwt jwt);

    /**
     * 全文搜索（公开；blog.search.fulltext-enabled=true 走 ngram FULLTEXT，否则 LIKE 兜底）。
     * 仅出对外可见文章（谓词见 ArticleVisibility）
     */
    default ArticleSearchResultVO search(String keyword, long page, long size) {
        return search(keyword, page, size, null, null, null, null, "relevance");
    }

    ArticleSearchResultVO search(String keyword, long page, long size, Long categoryId, Long tagId,
                                 LocalDate publishedFrom, LocalDate publishedTo, String sort);

    /**
     * 归档（公开）：可见文章按月分组，月份倒序，月内按发布时间倒序
     */
    List<ArchiveMonthVO> archives();
}
