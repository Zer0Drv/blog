package com.zer0drv.blog.article.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.domain.ArticleTag;
import com.zer0drv.blog.article.dto.ArticleSaveDTO;
import com.zer0drv.blog.article.dto.ArticleStatusDTO;
import com.zer0drv.blog.article.enums.ArticleStatus;
import com.zer0drv.blog.article.enums.EditorType;
import com.zer0drv.blog.article.mapper.ArticleMapper;
import com.zer0drv.blog.article.mapper.ArticleTagMapper;
import com.zer0drv.blog.article.service.ArticleService;
import com.zer0drv.blog.article.vo.ArticleAuthorVO;
import com.zer0drv.blog.article.vo.ArticleDetailVO;
import com.zer0drv.blog.article.vo.ArticleListVO;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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

    private final ArticleTagMapper articleTagMapper;
    private final TagMapper tagMapper;
    private final CategoryMapper categoryMapper;
    private final UserService userService;
    private final Converter converter;
    private final CommentMapper commentMapper;
    private final CommentLikeMapper commentLikeMapper;
    private final ArticleLikeMapper articleLikeMapper;
    private final ArticleFavoriteMapper articleFavoriteMapper;

    @Override
    public PageResult<ArticleListVO> pagePublished(long page, long size, String keyword, Long tagId, Long categoryId) {
        LambdaQueryWrapper<Article> wrapper = Wrappers.lambdaQuery(Article.class)
                .eq(Article::getStatus, ArticleStatus.PUBLISHED.name())
                .eq(Objects.nonNull(categoryId), Article::getCategoryId, categoryId)
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
        // 非发布状态仅作者本人 / ADMIN 可见；对匿名与非作者不暴露文章存在性，统一报不存在
        if (!published && !isAuthorOrAdmin(article, jwt)) {
            throw new BusinessException(StatusCode.ARTICLE_NOT_EXIST);
        }
        // 浏览量原子自增（一条 update 语句，不先查后写）；草稿 / 下架详情不加
        if (published) {
            baseMapper.update(null, Wrappers.lambdaUpdate(Article.class)
                    .setSql("view_count = view_count + 1")
                    .eq(Article::getId, id));
        }
        ArticleDetailVO vo = converter.convert(article, ArticleDetailVO.class);
        fillAssociations(List.of(vo), List.of(article));
        fillInteraction(vo, article, published, jwt);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ArticleSaveDTO dto, Jwt jwt) {
        validateSaveDTO(dto);
        Article article = new Article();
        article.setTitle(dto.getTitle());
        article.setContent(dto.getContent());
        article.setEditorType(dto.getEditorType());
        article.setSummary(resolveSummary(dto));
        article.setCover(Objects.isNull(dto.getCover()) ? "" : dto.getCover());
        article.setCategoryId(dto.getCategoryId());
        article.setAuthorId(JwtSubjects.userIdOf(jwt));
        String status = resolveSaveStatus(dto.getStatus());
        article.setStatus(status);
        // 新建直接发布：写首次发布时间
        if (ArticleStatus.PUBLISHED.name().equals(status)) {
            article.setPublishTime(LocalDateTime.now());
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
        article.setTitle(dto.getTitle());
        article.setContent(dto.getContent());
        article.setEditorType(dto.getEditorType());
        article.setSummary(resolveSummary(dto));
        article.setCover(Objects.isNull(dto.getCover()) ? "" : dto.getCover());
        article.setCategoryId(dto.getCategoryId());
        if (Objects.nonNull(dto.getStatus()) && !dto.getStatus().isBlank()) {
            String status = resolveSaveStatus(dto.getStatus());
            article.setStatus(status);
            // 首次发布写发布时间，已发布过的不覆盖
            if (ArticleStatus.PUBLISHED.name().equals(status) && Objects.isNull(article.getPublishTime())) {
                article.setPublishTime(LocalDateTime.now());
            }
        }
        updateById(article);
        replaceTagRelations(article.getId(), dto.getTagIds());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Jwt jwt) {
        Article article = requireArticle(id);
        assertAuthorOrAdmin(article, jwt);
        removeById(id);
        // 关联表无逻辑删除字段，物理删除
        articleTagMapper.delete(Wrappers.lambdaQuery(ArticleTag.class).eq(ArticleTag::getArticleId, id));
        // M3 级联：评论点赞关联物理删除（先取评论id，逻辑删除后查不到）
        List<Long> commentIds = commentMapper.selectList(Wrappers.lambdaQuery(Comment.class)
                        .eq(Comment::getArticleId, id))
                .stream().map(Comment::getId).toList();
        if (!commentIds.isEmpty()) {
            commentLikeMapper.delete(Wrappers.lambdaQuery(CommentLike.class)
                    .in(CommentLike::getCommentId, commentIds));
        }
        // 该文章的评论走 MP 逻辑删除（comment 有 deleted 字段）
        commentMapper.delete(Wrappers.lambdaQuery(Comment.class).eq(Comment::getArticleId, id));
        // 文章点赞 / 收藏关联物理删除
        articleLikeMapper.delete(Wrappers.lambdaQuery(ArticleLike.class).eq(ArticleLike::getArticleId, id));
        articleFavoriteMapper.delete(Wrappers.lambdaQuery(ArticleFavorite.class).eq(ArticleFavorite::getArticleId, id));
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
            article.setPublishTime(LocalDateTime.now());
        }
        updateById(article);
    }

    @Override
    public PageResult<ArticleListVO> pageMine(long page, long size, String status, Jwt jwt) {
        Long userId = JwtSubjects.userIdOf(jwt);
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
        String text = dto.getContent().replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
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
    private void fillInteraction(ArticleDetailVO vo, Article article, boolean published, Jwt jwt) {
        Long articleId = article.getId();
        // 已发布详情刚自增过一次，内存值 +1 与库内一致；未发布展示当前值
        long viewCount = Objects.isNull(article.getViewCount()) ? 0L : article.getViewCount();
        vo.setViewCount(published ? viewCount + 1 : viewCount);
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