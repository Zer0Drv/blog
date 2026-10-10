package com.zer0drv.blog.article.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.dto.ArticleSaveDTO;
import com.zer0drv.blog.article.dto.ArticleStatusDTO;
import com.zer0drv.blog.article.dto.AutosaveDTO;
import com.zer0drv.blog.article.vo.ArchiveMonthVO;
import com.zer0drv.blog.article.vo.ArticleDetailVO;
import com.zer0drv.blog.article.vo.ArticleExportVO;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.article.vo.ArticleVersionDetailVO;
import com.zer0drv.blog.article.vo.ArticleVersionVO;
import com.zer0drv.blog.common.response.PageResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * @author Yoruhaki
 */
public interface ArticleService extends IService<Article> {

    /**
     * 已发布文章分页列表（按发布时间倒序，支持关键字 / 标签 / 分类过滤）
     */
    PageResult<ArticleListVO> pagePublished(long page, long size, String keyword, Long tagId, Long categoryId);

    /**
     * 文章详情。匿名 / 非作者仅可见 PUBLISHED；作者本人或 ADMIN 可看任意状态。
     * jwt 可为 null（匿名访问）。
     */
    ArticleDetailVO getDetail(Long id, Jwt jwt);

    /**
     * 新建文章（允许直接发布），返回文章id
     */
    Long create(ArticleSaveDTO dto, Jwt jwt);

    /**
     * 编辑文章（仅本人或 ADMIN）
     */
    void update(Long id, ArticleSaveDTO dto, Jwt jwt);

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
     * status 与 publishTime 不动；覆盖前对当前行产一个新快照（恢复也留痕）
     */
    void restoreVersion(Long id, Integer version, Jwt jwt);

    /**
     * 自动保存草稿（仅本人或 ADMIN，仅编辑已有文章）：存 Redis，TTL 2 小时，返回 {savedAt}
     */
    Map<String, Object> saveAutosave(Long id, AutosaveDTO dto, Jwt jwt);

    /**
     * 读取自动保存草稿（仅本人或 ADMIN）：不存在返回 {exists:false}
     */
    Map<String, Object> getAutosave(Long id, Jwt jwt);

    /**
     * 全文搜索（公开；blog.search.fulltext-enabled=true 走 ngram FULLTEXT，否则 LIKE 兜底）。
     * 仅出对外可见文章（谓词见 ArticleVisibility）
     */
    PageResult<ArticleListVO> search(String keyword, long page, long size);

    /**
     * 归档（公开）：可见文章按月分组，月份倒序，月内按发布时间倒序
     */
    List<ArchiveMonthVO> archives();

    /**
     * 导出文章为文件（md / html）。可见性与 {@link #getDetail} 完全一致
     * （已发布=公开；草稿/下架=本人或 ADMIN，其余一律 ARTICLE_NOT_EXIST）。
     * format 可空：缺省按文章 editorType（MARKDOWN→md，RICHTEXT→html）；
     * 内容为正文原文 + YAML front matter，不做 Markdown 渲染转换。
     */
    ArticleExportVO exportArticle(Long id, String format, Jwt jwt);

    /**
     * 导入文章文件并创建为当前用户的 DRAFT 草稿（复用 {@link #create}，
     * RICHTEXT 内容随之过 jsoup 白名单清洗）。
     * .md/.markdown/.txt → MARKDOWN；.html/.htm → RICHTEXT；≤2MB；UTF-8 解码；
     * Markdown 取正文首个一级标题作标题（该行从正文移除），否则用文件名去扩展名。
     */
    ArticleDetailVO importArticle(MultipartFile file, Jwt jwt);
}
