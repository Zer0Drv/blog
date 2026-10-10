package com.zer0drv.blog.article.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zer0drv.blog.article.api.ArticleCatalog;
import com.zer0drv.blog.article.api.ArticleFeedEntry;
import com.zer0drv.blog.article.api.ArticleRef;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.domain.ArticleVisibility;
import com.zer0drv.blog.article.mapper.ArticleMapper;
import com.zer0drv.blog.article.service.ArticleService;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link ArticleCatalog} 实现（article 内部包）。可见性判定一律转发
 * {@link ArticleVisibility}（谓词单一出处），列表装配复用 {@link ArticleService#assemble}。
 *
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class ArticleCatalogImpl implements ArticleCatalog {

    private final ArticleMapper articleMapper;
    private final ArticleService articleService;

    @Override
    public boolean isVisible(long articleId) {
        // MP 逻辑删除自动拼 deleted=0，已删除行查不出来，按不可见处理
        return ArticleVisibility.isVisible(articleMapper.selectById(articleId));
    }

    @Override
    public ArticleRef requireVisible(long articleId) {
        Article article = articleMapper.selectById(articleId);
        // 不可见对外一律表现为「不存在」，不泄露未发布内容的存在性
        if (!ArticleVisibility.isVisible(article)) {
            throw new BusinessException(StatusCode.ARTICLE_NOT_EXIST);
        }
        return toRef(article);
    }

    @Override
    public Optional<ArticleRef> findRef(Long id) {
        if (Objects.isNull(id)) {
            return Optional.empty();
        }
        return Optional.ofNullable(articleMapper.selectById(id)).map(ArticleCatalogImpl::toRef);
    }

    @Override
    public List<ArticleRef> listRefsByIds(Collection<Long> ids) {
        if (Objects.isNull(ids) || ids.isEmpty()) {
            return List.of();
        }
        return articleMapper.selectByIds(ids).stream().map(ArticleCatalogImpl::toRef).toList();
    }

    @Override
    public PageResult<ArticleListVO> pageVisibleByAuthors(Collection<Long> authorIds, long page, long size) {
        if (Objects.isNull(authorIds) || authorIds.isEmpty()) {
            // 空集合不触库（避免 in () 语法问题），直接返回空页
            return PageResult.of(List.of(), 0, page, size);
        }
        Page<Article> result = articleMapper.selectPage(new Page<>(page, size),
                ArticleVisibility.apply(Wrappers.lambdaQuery(Article.class))
                        .in(Article::getAuthorId, authorIds)
                        .orderByDesc(Article::getPublishTime));
        return PageResult.of(articleService.assemble(result.getRecords()), result.getTotal(), page, size);
    }

    @Override
    public long countVisibleByAuthor(long authorId) {
        return articleMapper.selectCount(ArticleVisibility.apply(Wrappers.lambdaQuery(Article.class))
                .eq(Article::getAuthorId, authorId));
    }

    @Override
    public List<ArticleFeedEntry> listVisibleLatest(int limit) {
        Page<Article> page = articleMapper.selectPage(new Page<>(1, limit),
                ArticleVisibility.apply(Wrappers.lambdaQuery(Article.class))
                        .orderByDesc(Article::getPublishTime));
        return page.getRecords().stream().map(ArticleCatalogImpl::toFeedEntry).toList();
    }

    @Override
    public List<ArticleListVO> listByIdsPreserveOrder(Collection<Long> ids) {
        if (Objects.isNull(ids) || ids.isEmpty()) {
            return List.of();
        }
        // 已删除的 id 查不出来，内存按入参顺序过滤重排（收藏夹语义：不过滤可见性）
        Map<Long, Article> articleMap = articleMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(Article::getId, Function.identity()));
        List<Article> ordered = ids.stream()
                .map(articleMap::get)
                .filter(Objects::nonNull)
                .toList();
        return articleService.assemble(ordered);
    }

    private static ArticleRef toRef(Article article) {
        return new ArticleRef(article.getId(), article.getAuthorId(), article.getTitle());
    }

    private static ArticleFeedEntry toFeedEntry(Article article) {
        return new ArticleFeedEntry(article.getId(), article.getAuthorId(), article.getTitle(),
                article.getSummary(), article.getPublishTime(), article.getUpdateTime());
    }
}
