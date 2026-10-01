package com.zer0drv.blog.article.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.domain.ArticleTag;
import com.zer0drv.blog.article.domain.ArticleVersion;
import com.zer0drv.blog.article.dto.ArticleSaveDTO;
import com.zer0drv.blog.article.dto.ArticleStatusDTO;
import com.zer0drv.blog.article.dto.AutosaveDTO;
import com.zer0drv.blog.article.enums.ArticleStatus;
import com.zer0drv.blog.article.enums.EditorType;
import com.zer0drv.blog.article.mapper.ArticleMapper;
import com.zer0drv.blog.article.mapper.ArticleTagMapper;
import com.zer0drv.blog.article.mapper.ArticleVersionMapper;
import com.zer0drv.blog.article.service.ArticleService;
import com.zer0drv.blog.article.vo.ArchiveMonthVO;
import com.zer0drv.blog.article.vo.ArticleAuthorVO;
import com.zer0drv.blog.article.vo.ArticleDetailVO;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.article.vo.ArticleVersionDetailVO;
import com.zer0drv.blog.article.vo.ArticleVersionVO;
import com.zer0drv.blog.category.domain.Category;
import com.zer0drv.blog.category.mapper.CategoryMapper;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.common.util.JwtSubjects;
import com.zer0drv.blog.comment.domain.Comment;
import com.zer0drv.blog.comment.enums.CommentStatus;
import com.zer0drv.blog.comment.mapper.CommentMapper;
import com.zer0drv.blog.interaction.domain.ArticleFavorite;
import com.zer0drv.blog.interaction.domain.ArticleLike;
import com.zer0drv.blog.interaction.domain.CommentLike;
import com.zer0drv.blog.interaction.mapper.ArticleFavoriteMapper;
import com.zer0drv.blog.interaction.mapper.ArticleLikeMapper;
import com.zer0drv.blog.interaction.mapper.CommentLikeMapper;
import com.zer0drv.blog.tag.domain.Tag;
import com.zer0drv.blog.tag.mapper.TagMapper;
import com.zer0drv.blog.tag.vo.TagVO;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.enums.UserRole;
import com.zer0drv.blog.user.service.UserService;
import io.github.linpeilie.Converter;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class ArticleServiceImpl extends ServiceImpl<ArticleMapper, Article> implements ArticleService {

    /**
     * 摘要缺省长度：取 content 纯文本前 200 字
     */
    private static final int SUMMARY_MAX_LENGTH = 200;

    /**
     * 定时发布最小提前量（秒）：publishTime 必须晚于当前时间至少这么久
     */
    private static final long SCHEDULE_MIN_LEAD_SECONDS = 60;

    /**
     * 自动保存草稿 Redis 键：blog:autosave:{articleId}:{userId}，TTL 2 小时
     */
    private static final String AUTOSAVE_KEY = "blog:autosave:%d:%d";
    private static final Duration AUTOSAVE_TTL = Duration.ofHours(2);

    /**
     * 归档月份格式：yyyy-MM
     */
    private static final DateTimeFormatter ARCHIVE_MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * 回收站伪状态（非 ArticleStatus 枚举值，service 层先行拦截）
     */
    private static final String PSEUDO_STATUS_TRASH = "TRASH";

    private final ArticleTagMapper articleTagMapper;
    private final TagMapper tagMapper;
    private final CategoryMapper categoryMapper;
    private final UserService userService;
    private final Converter converter;
    private final CommentMapper commentMapper;
    private final CommentLikeMapper commentLikeMapper;
    private final ArticleLikeMapper articleLikeMapper;
    private final ArticleFavoriteMapper articleFavoriteMapper;
    private final ArticleVersionMapper articleVersionMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 全文搜索开关（P0）：true 走 ngram FULLTEXT（仅 MySQL）；false 走 LIKE 兜底（H2 测试环境）
     */
    @Value("${blog.search.fulltext-enabled:true}")
    private boolean fulltextEnabled;

    @Override
    public PageResult<ArticleListVO> pagePublished(long page, long size, String keyword, Long tagId, Long categoryId) {
        LambdaQueryWrapper<Article> wrapper = Wrappers.lambdaQuery(Article.class)
                .eq(Article::getStatus, ArticleStatus.PUBLISHED.name())
                // P0 定时发布可见性谓词：publish_time 非空且已到（le 天然排除 NULL）
                .le(Article::getPublishTime, LocalDateTime.now())
                .eq(Objects.nonNull(categoryId), Article::getCategoryId, categoryId)
                // M5：置顶优先，其后按发布时间倒序
                .orderByDesc(Article::getIsTop)
                .orderByDesc(Article::getPublishTime);
        if (Objects.nonNull(keyword) && !keyword.isBlank()) {
            // keyword 模糊匹配 title / summary
            wrapper.and(w -> w.like(Article::getTitle, keyword).or().like(Article::getSummary, keyword));
        }
        if (Objects.nonNull(tagId)) {
            List<Long> articleIds = articleTagMapper.selectList(Wrappers.lambdaQuery(ArticleTag.class)
                            .eq(ArticleTag::getTagId, tagId))
                    .stream().map(ArticleTag::getArticleId).toList();
            if (articleIds.isEmpty()) {
                return PageResult.of(List.of(), 0, page, size);
            }
            wrapper.in(Article::getId, articleIds);
        }
        Page<Article> result = page(new Page<>(page, size), wrapper);
        return PageResult.of(assemble(result.getRecords()), result.getTotal(), page, size);
    }

    @Override
    public ArticleDetailVO getDetail(Long id, Jwt jwt) {
        Article article = getById(id);
        if (Objects.isNull(article)) {
            throw new BusinessException(StatusCode.ARTICLE_NOT_EXIST);
        }
        boolean published = ArticleStatus.PUBLISHED.name().equals(article.getStatus());
        // P0 定时发布：PUBLISHED 但 publish_time 未到的文章对公众仍不可见
        boolean visible = published && Objects.nonNull(article.getPublishTime())
                && !article.getPublishTime().isAfter(LocalDateTime.now());
        // 非可见状态仅作者本人 / ADMIN 可见；对匿名与非作者不暴露文章存在性，统一报不存在
        if (!visible && !isAuthorOrAdmin(article, jwt)) {
            throw new BusinessException(StatusCode.ARTICLE_NOT_EXIST);
        }
        // 浏览量原子自增（一条 update 语句，不先查后写）；草稿 / 下架 / 定时中详情不加
        if (visible) {
            baseMapper.update(null, Wrappers.lambdaUpdate(Article.class)
                    .setSql("view_count = view_count + 1")
                    .eq(Article::getId, id));
        }
        ArticleDetailVO vo = converter.convert(article, ArticleDetailVO.class);
        fillAssociations(List.of(vo), List.of(article));
        fillInteraction(vo, article, visible, jwt);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ArticleSaveDTO dto, Jwt jwt) {
        validateSaveDTO(dto);
        Article article = new Article();
        article.setTitle(dto.getTitle());
        // 存储型 XSS 防线（blog-ui#9 后端部分）：RICHTEXT 内容入库前白名单清洗
        String content = sanitizeContent(dto.getContent(), dto.getEditorType());
        article.setContent(content);
        article.setEditorType(dto.getEditorType());
        article.setSummary(resolveSummary(dto));
        article.setCover(Objects.isNull(dto.getCover()) ? "" : dto.getCover());
        article.setCategoryId(dto.getCategoryId());
        article.setAuthorId(JwtSubjects.userIdOf(jwt));
        article.setContentText(toPlainText(content));
        String status = resolveSaveStatus(dto.getStatus());
        article.setStatus(status);
        // 新建直接发布：写首次发布时间（可定时；status=DRAFT 时忽略 publishTime）
        if (ArticleStatus.PUBLISHED.name().equals(status)) {
            article.setPublishTime(resolvePublishTimeOnPublish(dto.getPublishTime()));
        }
        save(article);
        replaceTagRelations(article.getId(), dto.getTagIds());
        return article.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ArticleSaveDTO dto, Jwt jwt) {
        Article article = requireArticle(id);
        assertAuthorOrAdmin(article, jwt);
        validateSaveDTO(dto);
        // P0 版本历史：先把更新前的整行快照为新版本（同事务；create/updateStatus 不产快照）
        snapshotVersion(article);
        article.setTitle(dto.getTitle());
        // 存储型 XSS 防线（blog-ui#9 后端部分）：RICHTEXT 内容入库前白名单清洗
        String content = sanitizeContent(dto.getContent(), dto.getEditorType());
        article.setContent(content);
        article.setContentText(toPlainText(content));
        article.setEditorType(dto.getEditorType());
        article.setSummary(resolveSummary(dto));
        article.setCover(Objects.isNull(dto.getCover()) ? "" : dto.getCover());
        article.setCategoryId(dto.getCategoryId());
        if (Objects.nonNull(dto.getStatus()) && !dto.getStatus().isBlank()) {
            String status = resolveSaveStatus(dto.getStatus());
            article.setStatus(status);
            if (ArticleStatus.PUBLISHED.name().equals(status)) {
                if (Objects.isNull(article.getPublishTime())) {
                    // 首次发布：写发布时间（可定时）
                    article.setPublishTime(resolvePublishTimeOnPublish(dto.getPublishTime()));
                } else if (article.getPublishTime().isAfter(LocalDateTime.now())
                        && Objects.nonNull(dto.getPublishTime())) {
                    // 定时中（未上线）：允许改档期；已上线（publish_time <= now）的文章忽略 DTO 的 publishTime，不可改档期
                    article.setPublishTime(resolvePublishTimeOnPublish(dto.getPublishTime()));
                }
            }
        }
        updateById(article);
        replaceTagRelations(article.getId(), dto.getTagIds());
        // P0 自动保存：正式保存成功后删除该操作者的草稿
        deleteAutosave(id, JwtSubjects.userIdOf(jwt));
    }

    /**
     * 删除文章（P0 回收站）：只逻辑删 article 本行，不再级联清 article_tag / 评论 / 点赞收藏，
     * 保证回收站可完整恢复；级联物理删移到 forceDelete
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Jwt jwt) {
        Article article = requireArticle(id);
        assertAuthorOrAdmin(article, jwt);
        removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restore(Long id, Jwt jwt) {
        // MP 逻辑删除会自动拼 deleted=0，回收站行必须手写 SQL 查
        Article article = baseMapper.selectDeletedById(id);
        if (Objects.isNull(article)) {
            throw new BusinessException(StatusCode.ARTICLE_NOT_EXIST);
        }
        assertAuthorOrAdmin(article, jwt);
        // 恢复回草稿态，不直接上线
        baseMapper.restoreDeleted(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forceDelete(Long id, Jwt jwt) {
        // 彻底删除允许针对回收站中的行，存在性校验含已逻辑删除的行
        Article article = baseMapper.selectAnyById(id);
        if (Objects.isNull(article)) {
            throw new BusinessException(StatusCode.ARTICLE_NOT_EXIST);
        }
        assertAuthorOrAdmin(article, jwt);
        // 物理删除本行 + 级联物理删关联
        baseMapper.physicalDeleteById(id);
        // article_tag 无逻辑删除字段，物理删除
        articleTagMapper.delete(Wrappers.lambdaQuery(ArticleTag.class).eq(ArticleTag::getArticleId, id));
        // 评论点赞关联物理删除（含已逻辑删除评论的点赞，手写 SQL 取全量评论 id）
        List<Long> commentIds = baseMapper.selectAllCommentIdsByArticleId(id);
        if (!commentIds.isEmpty()) {
            commentLikeMapper.delete(Wrappers.lambdaQuery(CommentLike.class)
                    .in(CommentLike::getCommentId, commentIds));
        }
        // 该文章的评论（含已逻辑删除）物理删除
        baseMapper.physicalDeleteCommentsByArticleId(id);
        // 文章点赞 / 收藏关联物理删除
        articleLikeMapper.delete(Wrappers.lambdaQuery(ArticleLike.class).eq(ArticleLike::getArticleId, id));
        articleFavoriteMapper.delete(Wrappers.lambdaQuery(ArticleFavorite.class).eq(ArticleFavorite::getArticleId, id));
        // 全部版本快照物理删除
        articleVersionMapper.physicalDeleteByArticleId(id);
        // 清作者与操作者的自动保存草稿
        deleteAutosave(id, article.getAuthorId());
        Long operatorId = JwtSubjects.userIdOf(jwt);
        if (!operatorId.equals(article.getAuthorId())) {
            deleteAutosave(id, operatorId);
        }
    }

    @Override
    public void updateStatus(Long id, ArticleStatusDTO dto, Jwt jwt) {
        Article article = requireArticle(id);
        assertAuthorOrAdmin(article, jwt);
        if (!ArticleStatus.isValid(dto.getStatus())) {
            throw new BusinessException(StatusCode.ARTICLE_STATUS_INVALID);
        }
        article.setStatus(dto.getStatus());
        if (ArticleStatus.PUBLISHED.name().equals(dto.getStatus()) && Objects.isNull(article.getPublishTime())) {
            // 截断到秒，原因同 resolvePublishTimeOnPublish
            article.setPublishTime(LocalDateTime.now().withNano(0));
        }
        updateById(article);
    }

    @Override
    public PageResult<ArticleListVO> pageMine(long page, long size, String status, Jwt jwt) {
        Long userId = JwtSubjects.userIdOf(jwt);
        // P0 回收站伪状态：先拦截，MP 逻辑删除会自动拼 deleted=0，必须手写 SQL 绕过
        if (PSEUDO_STATUS_TRASH.equals(status)) {
            Page<Article> trash = baseMapper.selectTrashPage(new Page<>(page, size), userId);
            return PageResult.of(assemble(trash.getRecords()), trash.getTotal(), page, size);
        }
        LambdaQueryWrapper<Article> wrapper = Wrappers.lambdaQuery(Article.class)
                .eq(Article::getAuthorId, userId)
                .orderByDesc(Article::getUpdateTime);
        if (Objects.nonNull(status) && !status.isBlank()) {
            if (!ArticleStatus.isValid(status)) {
                throw new BusinessException(StatusCode.ARTICLE_STATUS_INVALID);
            }
            wrapper.eq(Article::getStatus, status);
        }
        Page<Article> result = page(new Page<>(page, size), wrapper);
        return PageResult.of(assemble(result.getRecords()), result.getTotal(), page, size);
    }

    @Override
    public List<ArticleVersionVO> listVersions(Long id, Jwt jwt) {
        Article article = requireArticle(id);
        assertAuthorOrAdmin(article, jwt);
        return articleVersionMapper.selectList(Wrappers.lambdaQuery(ArticleVersion.class)
                        .eq(ArticleVersion::getArticleId, id)
                        .orderByDesc(ArticleVersion::getVersion))
                .stream().map(this::toVersionVO).toList();
    }

    @Override
    public ArticleVersionDetailVO getVersion(Long id, Integer version, Jwt jwt) {
        Article article = requireArticle(id);
        assertAuthorOrAdmin(article, jwt);
        return toVersionDetailVO(requireVersion(id, version));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restoreVersion(Long id, Integer version, Jwt jwt) {
        Article article = requireArticle(id);
        assertAuthorOrAdmin(article, jwt);
        ArticleVersion snapshot = requireVersion(id, version);
        // 恢复也留痕：覆盖前先对当前行产一个新快照
        snapshotVersion(article);
        article.setTitle(snapshot.getTitle());
        article.setSummary(snapshot.getSummary());
        // 存量快照可能产自 XSS 清洗上线前，恢复时同样过白名单（幂等，已清洗内容不受影响）
        String content = sanitizeContent(snapshot.getContent(), snapshot.getEditorType());
        article.setContent(content);
        article.setContentText(toPlainText(content));
        article.setEditorType(snapshot.getEditorType());
        article.setCover(snapshot.getCover());
        article.setCategoryId(snapshot.getCategoryId());
        // status / publishTime 不动（SPEC：恢复不回改发布状态与档期）
        updateById(article);
    }

    @Override
    public Map<String, Object> saveAutosave(Long id, AutosaveDTO dto, Jwt jwt) {
        Article article = requireArticle(id);
        assertAuthorOrAdmin(article, jwt);
        Long userId = JwtSubjects.userIdOf(jwt);
        long savedAt = System.currentTimeMillis();
        Map<String, Object> draft = new HashMap<>();
        draft.put("title", dto.getTitle());
        draft.put("content", dto.getContent());
        draft.put("summary", dto.getSummary());
        draft.put("cover", dto.getCover());
        draft.put("categoryId", dto.getCategoryId());
        draft.put("savedAt", savedAt);
        stringRedisTemplate.opsForValue().set(autosaveKey(id, userId),
                objectMapper.writeValueAsString(draft), AUTOSAVE_TTL);
        return Map.of("savedAt", savedAt);
    }

    @Override
    public Map<String, Object> getAutosave(Long id, Jwt jwt) {
        Article article = requireArticle(id);
        assertAuthorOrAdmin(article, jwt);
        Long userId = JwtSubjects.userIdOf(jwt);
        String json = stringRedisTemplate.opsForValue().get(autosaveKey(id, userId));
        if (Objects.isNull(json) || json.isBlank()) {
            return Map.of("exists", false);
        }
        try {
            Map<String, Object> draft = objectMapper.readValue(json, Map.class);
            draft.put("exists", true);
            return draft;
        } catch (RuntimeException e) {
            // 草稿 JSON 损坏时按不存在处理，不影响正式编辑
            return Map.of("exists", false);
        }
    }

    @Override
    public PageResult<ArticleListVO> search(String keyword, long page, long size) {
        if (Objects.isNull(keyword) || keyword.isBlank()) {
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(), "搜索关键字不能为空");
        }
        String kw = keyword.trim();
        Page<Article> result;
        if (fulltextEnabled) {
            // 生产（MySQL）：ngram FULLTEXT，MATCH...AGAINST 仅 MySQL 可跑
            result = baseMapper.searchByFulltext(new Page<>(page, size), kw);
        } else {
            // 兜底（H2 测试 / 未建全文索引）：title/summary/content_text 三路 LIKE
            result = page(new Page<>(page, size), Wrappers.lambdaQuery(Article.class)
                    .eq(Article::getStatus, ArticleStatus.PUBLISHED.name())
                    .le(Article::getPublishTime, LocalDateTime.now())
                    .and(w -> w.like(Article::getTitle, kw)
                            .or().like(Article::getSummary, kw)
                            .or().like(Article::getContentText, kw))
                    .orderByDesc(Article::getPublishTime));
        }
        return PageResult.of(assemble(result.getRecords()), result.getTotal(), page, size);
    }

    @Override
    public List<ArchiveMonthVO> archives() {
        // 可见性谓词同 §1.3；只取三列，Java 内存按月分组（个人博客量级，不用 SQL 分组方言）
        List<Article> articles = list(Wrappers.lambdaQuery(Article.class)
                .select(Article::getId, Article::getTitle, Article::getPublishTime)
                .eq(Article::getStatus, ArticleStatus.PUBLISHED.name())
                .le(Article::getPublishTime, LocalDateTime.now())
                .orderByDesc(Article::getPublishTime));
        // LinkedHashMap 保持首见顺序：publish_time 倒序遍历 → 月份天然倒序，月内文章亦倒序
        Map<String, List<ArchiveMonthVO.Item>> grouped = new LinkedHashMap<>();
        for (Article article : articles) {
            ArchiveMonthVO.Item item = new ArchiveMonthVO.Item();
            item.setId(article.getId());
            item.setTitle(article.getTitle());
            item.setPublishTime(article.getPublishTime());
            grouped.computeIfAbsent(article.getPublishTime().format(ARCHIVE_MONTH_FORMAT),
                    k -> new ArrayList<>()).add(item);
        }
        return grouped.entrySet().stream().map(entry -> {
            ArchiveMonthVO vo = new ArchiveMonthVO();
            vo.setMonth(entry.getKey());
            vo.setCount(entry.getValue().size());
            vo.setArticles(entry.getValue());
            return vo;
        }).toList();
    }

    /**
     * 文章版本快照：把当前行（更新前的旧行）存为「当前最大版本 + 1」。
     * 仅 update / restoreVersion 调用；create / updateStatus 不产快照；标签不纳入版本（SPEC 决策）。
     */
    private void snapshotVersion(Article article) {
        ArticleVersion latest = articleVersionMapper.selectOne(Wrappers.lambdaQuery(ArticleVersion.class)
                .eq(ArticleVersion::getArticleId, article.getId())
                .orderByDesc(ArticleVersion::getVersion)
                .last("LIMIT 1"));
        ArticleVersion snapshot = new ArticleVersion();
        snapshot.setArticleId(article.getId());
        snapshot.setVersion(Objects.isNull(latest) ? 1 : latest.getVersion() + 1);
        snapshot.setTitle(article.getTitle());
        snapshot.setSummary(Objects.isNull(article.getSummary()) ? "" : article.getSummary());
        snapshot.setContent(article.getContent());
        snapshot.setEditorType(article.getEditorType());
        snapshot.setCover(Objects.isNull(article.getCover()) ? "" : article.getCover());
        snapshot.setCategoryId(article.getCategoryId());
        articleVersionMapper.insert(snapshot);
    }

    private ArticleVersion requireVersion(Long articleId, Integer version) {
        ArticleVersion snapshot = articleVersionMapper.selectOne(Wrappers.lambdaQuery(ArticleVersion.class)
                .eq(ArticleVersion::getArticleId, articleId)
                .eq(ArticleVersion::getVersion, version));
        if (Objects.isNull(snapshot)) {
            throw new BusinessException(StatusCode.ARTICLE_VERSION_NOT_EXIST);
        }
        return snapshot;
    }

    /**
     * 发布时的发布时间：publishTime 为空写 now；非空即定时发布，
     * 必须晚于当前时间至少 60 秒，否则 PARAM_INVALID
     */
    private LocalDateTime resolvePublishTimeOnPublish(LocalDateTime publishTime) {
        if (Objects.isNull(publishTime)) {
            // 截断到秒：DATETIME 精度为秒且会四舍五入，带纳秒的 now() 可能进位到未来，
            // 导致「立即发布」的文章在 publish_time <= now 谓词下短暂不可见
            return LocalDateTime.now().withNano(0);
        }
        if (!publishTime.isAfter(LocalDateTime.now().plusSeconds(SCHEDULE_MIN_LEAD_SECONDS))) {
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(), "定时发布时间必须晚于当前时间");
        }
        return publishTime;
    }

    /**
     * 富文本 XSS 清洗（blog-ui#9 后端部分）：RICHTEXT 内容入库前用 jsoup Safelist.relaxed()
     * 白名单清洗（允许常见排版标签与 img 的 http/https src，剥离 script/on* 事件/危险协议）；
     * MARKDOWN 内容按原文存储——Markdown 源码不是 HTML，清洗会破坏合法语法，
     * 渲染侧由前端 DOMPurify 兜底（与本清洗构成纵深防御）。
     */
    private String sanitizeContent(String content, String editorType) {
        if (Objects.isNull(content) || content.isBlank()) {
            return content;
        }
        if (EditorType.RICHTEXT.name().equals(editorType)) {
            return Jsoup.clean(content, Safelist.relaxed());
        }
        return content;
    }

    /**
     * 正文清洗为纯文本（全文搜索 content_text 用）：去 HTML 标签 → 去 Markdown 控制符 → 压缩空白
     */
    private String toPlainText(String content) {
        if (Objects.isNull(content)) {
            return "";
        }
        return content
                .replaceAll("<[^>]+>", " ")
                .replaceAll("[#*`>\\-+=|\\[\\]()!~_.]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String autosaveKey(Long articleId, Long userId) {
        return AUTOSAVE_KEY.formatted(articleId, userId);
    }

    private void deleteAutosave(Long articleId, Long userId) {
        stringRedisTemplate.delete(autosaveKey(articleId, userId));
    }

    private ArticleVersionVO toVersionVO(ArticleVersion snapshot) {
        ArticleVersionVO vo = new ArticleVersionVO();
        vo.setId(snapshot.getId());
        vo.setVersion(snapshot.getVersion());
        vo.setTitle(snapshot.getTitle());
        vo.setCreateTime(snapshot.getCreateTime());
        return vo;
    }

    private ArticleVersionDetailVO toVersionDetailVO(ArticleVersion snapshot) {
        ArticleVersionDetailVO vo = new ArticleVersionDetailVO();
        vo.setId(snapshot.getId());
        vo.setVersion(snapshot.getVersion());
        vo.setTitle(snapshot.getTitle());
        vo.setCreateTime(snapshot.getCreateTime());
        vo.setSummary(snapshot.getSummary());
        vo.setContent(snapshot.getContent());
        vo.setEditorType(snapshot.getEditorType());
        vo.setCover(snapshot.getCover());
        vo.setCategoryId(snapshot.getCategoryId());
        return vo;
    }

    /**
     * 校验保存入参：编辑器类型、分类存在性、标签存在性
     */
    private void validateSaveDTO(ArticleSaveDTO dto) {
        if (!EditorType.isValid(dto.getEditorType())) {
            throw new BusinessException(StatusCode.EDITOR_TYPE_INVALID);
        }
        if (Objects.nonNull(dto.getCategoryId())) {
            Category category = categoryMapper.selectById(dto.getCategoryId());
            if (Objects.isNull(category)) {
                throw new BusinessException(StatusCode.CATEGORY_NOT_EXIST);
            }
        }
        if (Objects.nonNull(dto.getTagIds()) && !dto.getTagIds().isEmpty()) {
            List<Tag> tags = tagMapper.selectByIds(dto.getTagIds());
            if (tags.size() != dto.getTagIds().stream().distinct().count()) {
                throw new BusinessException(StatusCode.TAG_NOT_EXIST);
            }
        }
    }

    /**
     * 保存入参状态：可空，仅允许 DRAFT / PUBLISHED（下架走状态变更接口）
     */
    private String resolveSaveStatus(String status) {
        if (Objects.isNull(status) || status.isBlank()) {
            return ArticleStatus.DRAFT.name();
        }
        if (!ArticleStatus.DRAFT.name().equals(status) && !ArticleStatus.PUBLISHED.name().equals(status)) {
            throw new BusinessException(StatusCode.ARTICLE_STATUS_INVALID);
        }
        return status;
    }

    /**
     * 摘要缺省取 content 纯文本前 200 字（去 HTML 标签、压缩空白）
     */
    private String resolveSummary(ArticleSaveDTO dto) {
        if (Objects.nonNull(dto.getSummary()) && !dto.getSummary().isBlank()) {
            return dto.getSummary();
        }
        // 与 content_text 同一套清洗（P0 抽出的 toPlainText）
        String text = toPlainText(dto.getContent());
        return text.length() <= SUMMARY_MAX_LENGTH ? text : text.substring(0, SUMMARY_MAX_LENGTH);
    }

    private Article requireArticle(Long id) {
        Article article = getById(id);
        if (Objects.isNull(article)) {
            throw new BusinessException(StatusCode.ARTICLE_NOT_EXIST);
        }
        return article;
    }

    /**
     * 仅作者本人或 ADMIN 可操作，否则 403
     */
    private void assertAuthorOrAdmin(Article article, Jwt jwt) {
        if (!isAuthorOrAdmin(article, jwt)) {
            throw new BusinessException(StatusCode.NOT_AUTHOR);
        }
    }

    private boolean isAuthorOrAdmin(Article article, Jwt jwt) {
        if (Objects.isNull(jwt)) {
            return false;
        }
        Long userId = JwtSubjects.userIdOf(jwt);
        return userId.equals(article.getAuthorId()) || isAdmin(jwt);
    }

    /**
     * 从 JWT 的 roles 声明判断是否为 ADMIN（签发时值形如 ROLE_ADMIN）
     */
    private boolean isAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return Objects.nonNull(roles) && roles.contains("ROLE_" + UserRole.ADMIN.name());
    }

    /**
     * 全量替换文章的标签关联
     */
    private void replaceTagRelations(Long articleId, List<Long> tagIds) {
        articleTagMapper.delete(Wrappers.lambdaQuery(ArticleTag.class).eq(ArticleTag::getArticleId, articleId));
        if (Objects.isNull(tagIds) || tagIds.isEmpty()) {
            return;
        }
        tagIds.stream().distinct().forEach(tagId -> {
            ArticleTag relation = new ArticleTag();
            relation.setArticleId(articleId);
            relation.setTagId(tagId);
            articleTagMapper.insert(relation);
        });
    }

    /**
     * 填充详情的互动数据：浏览量 / 点赞收藏评论数 / 当前用户点赞收藏状态。
     * likeCount / favoriteCount / commentCount 实时 count 关联表，不落列。
     */
    private void fillInteraction(ArticleDetailVO vo, Article article, boolean visible, Jwt jwt) {
        Long articleId = article.getId();
        // 可见详情刚自增过一次，内存值 +1 与库内一致；不可见（草稿/下架/定时中）展示当前值
        long viewCount = Objects.isNull(article.getViewCount()) ? 0L : article.getViewCount();
        vo.setViewCount(visible ? viewCount + 1 : viewCount);
        vo.setLikeCount(articleLikeMapper.selectCount(Wrappers.lambdaQuery(ArticleLike.class)
                .eq(ArticleLike::getArticleId, articleId)));
        vo.setFavoriteCount(articleFavoriteMapper.selectCount(Wrappers.lambdaQuery(ArticleFavorite.class)
                .eq(ArticleFavorite::getArticleId, articleId)));
        vo.setCommentCount(commentMapper.selectCount(Wrappers.lambdaQuery(Comment.class)
                .eq(Comment::getArticleId, articleId)
                .eq(Comment::getStatus, CommentStatus.NORMAL.name())));
        // 匿名一律 false；登录人实时查关联表
        boolean liked = false;
        boolean favorited = false;
        if (Objects.nonNull(jwt)) {
            Long userId = JwtSubjects.userIdOf(jwt);
            liked = articleLikeMapper.selectCount(Wrappers.lambdaQuery(ArticleLike.class)
                    .eq(ArticleLike::getArticleId, articleId)
                    .eq(ArticleLike::getUserId, userId)) > 0;
            favorited = articleFavoriteMapper.selectCount(Wrappers.lambdaQuery(ArticleFavorite.class)
                    .eq(ArticleFavorite::getArticleId, articleId)
                    .eq(ArticleFavorite::getUserId, userId)) > 0;
        }
        vo.setLiked(liked);
        vo.setFavorited(favorited);
    }

    /**
     * 批量组装列表 VO：作者 / 分类名 / 标签 内存联查，避免 N+1
     */
    @Override
    public List<ArticleListVO> assemble(List<Article> articles) {
        if (articles.isEmpty()) {
            return List.of();
        }
        List<ArticleListVO> vos = articles.stream()
                .map(article -> converter.convert(article, ArticleListVO.class))
                .toList();
        fillAssociations(new ArrayList<>(vos), articles);
        return vos;
    }

    /**
     * 为 VO 填充关联信息（作者 / 分类名 / 标签）。vos 与 articles 需按下标一一对应。
     */
    private void fillAssociations(List<? extends ArticleListVO> vos, List<Article> articles) {
        List<Long> articleIds = articles.stream().map(Article::getId).toList();
        Map<Long, List<TagVO>> tagsMap = tagsOfArticles(articleIds);
        Set<Long> authorIds = articles.stream().map(Article::getAuthorId).collect(Collectors.toSet());
        Map<Long, User> authorMap = userService.listByIds(authorIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Set<Long> categoryIds = articles.stream().map(Article::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> categoryNameMap = categoryIds.isEmpty() ? Map.of()
                : categoryMapper.selectByIds(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
        for (int i = 0; i < articles.size(); i++) {
            Article article = articles.get(i);
            ArticleListVO vo = vos.get(i);
            vo.setCategoryName(Objects.nonNull(article.getCategoryId())
                    ? categoryNameMap.get(article.getCategoryId()) : null);
            vo.setTags(tagsMap.getOrDefault(article.getId(), List.of()));
            User author = authorMap.get(article.getAuthorId());
            if (Objects.nonNull(author)) {
                vo.setAuthor(toAuthorVO(author));
            }
        }
    }

    /**
     * 作者信息内存组装（不 SQL join）
     */
    private ArticleAuthorVO toAuthorVO(User user) {
        ArticleAuthorVO authorVO = new ArticleAuthorVO();
        authorVO.setId(user.getId());
        authorVO.setUsername(user.getUsername());
        authorVO.setNickname(user.getNickname());
        authorVO.setAvatar(user.getAvatar());
        return authorVO;
    }

    /**
     * 按文章id分组查询标签
     */
    private Map<Long, List<TagVO>> tagsOfArticles(List<Long> articleIds) {
        List<ArticleTag> relations = articleTagMapper.selectList(Wrappers.lambdaQuery(ArticleTag.class)
                .in(ArticleTag::getArticleId, articleIds));
        if (relations.isEmpty()) {
            return Map.of();
        }
        Set<Long> tagIds = relations.stream().map(ArticleTag::getTagId).collect(Collectors.toSet());
        Map<Long, Tag> tagMap = tagMapper.selectByIds(tagIds).stream()
                .collect(Collectors.toMap(Tag::getId, Function.identity()));
        Map<Long, List<TagVO>> tagsMap = new HashMap<>();
        for (ArticleTag relation : relations) {
            Tag tag = tagMap.get(relation.getTagId());
            if (Objects.nonNull(tag)) {
                tagsMap.computeIfAbsent(relation.getArticleId(), k -> new ArrayList<>())
                        .add(converter.convert(tag, TagVO.class));
            }
        }
        return tagsMap;
    }
}
