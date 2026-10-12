package com.zer0drv.blog.article.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zer0drv.blog.article.api.ArticlePublishedEntry;
import com.zer0drv.blog.article.api.ArticleStatsPort;
import com.zer0drv.blog.article.api.ArticleStatus;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.mapper.ArticleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link ArticleStatsPort} 实现（article 内部包，issue #34 第五步）：
 * 统计 SQL 逐字节沿用自 AdminStatsServiceImpl 收口前的内联实现——
 * 管理端口径仅 {@code status=PUBLISHED}，不引入 publish_time 条件。
 *
 * @author Yoruhaki
 */
@Component
@RequiredArgsConstructor
public class ArticleStatsPortImpl implements ArticleStatsPort {

    private final ArticleMapper articleMapper;

    @Override
    public long countPublished() {
        return articleMapper.selectCount(Wrappers.lambdaQuery(Article.class)
                .eq(Article::getStatus, ArticleStatus.PUBLISHED.name()));
    }

    @Override
    public long countPublishedSince(LocalDateTime since) {
        return articleMapper.selectCount(Wrappers.lambdaQuery(Article.class)
                .eq(Article::getStatus, ArticleStatus.PUBLISHED.name())
                .ge(Article::getCreateTime, since));
    }

    @Override
    public long totalViews() {
        // 全站文章总浏览量：SUM(view_count)，空表 / 全 NULL 兜底 0
        List<Map<String, Object>> rows = articleMapper.selectMaps(new QueryWrapper<Article>()
                .select("IFNULL(SUM(view_count), 0) AS totalViews"));
        if (rows.isEmpty()) {
            return 0L;
        }
        Object total = rows.getFirst().get("totalViews");
        return Objects.nonNull(total) ? ((Number) total).longValue() : 0L;
    }

    @Override
    public List<ArticlePublishedEntry> listPublishedLatest(int limit) {
        return articleMapper.selectList(Wrappers.lambdaQuery(Article.class)
                        .eq(Article::getStatus, ArticleStatus.PUBLISHED.name())
                        .orderByDesc(Article::getPublishTime)
                        .last("LIMIT " + limit))
                .stream()
                .map(article -> new ArticlePublishedEntry(article.getId(), article.getAuthorId(),
                        article.getTitle(), article.getPublishTime()))
                .toList();
    }
}
