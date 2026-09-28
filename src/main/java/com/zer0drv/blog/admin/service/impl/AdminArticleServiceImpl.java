package com.zer0drv.blog.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zer0drv.blog.admin.dto.RecommendDTO;
import com.zer0drv.blog.admin.dto.TopDTO;
import com.zer0drv.blog.admin.service.AdminArticleService;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.enums.ArticleStatus;
import com.zer0drv.blog.article.mapper.ArticleMapper;
import com.zer0drv.blog.article.service.ArticleService;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class AdminArticleServiceImpl implements AdminArticleService {

    private static final short FLAG_OFF = 0;
    private static final short FLAG_ON = 1;

    private final ArticleMapper articleMapper;
    private final ArticleService articleService;

    @Override
    public PageResult<ArticleListVO> pageArticles(long page, long size, String status, String keyword, Long authorId) {
        if (Objects.nonNull(status) && !status.isBlank() && !ArticleStatus.isValid(status)) {
            throw new BusinessException(StatusCode.ARTICLE_STATUS_INVALID);
        }
        LambdaQueryWrapper<Article> wrapper = Wrappers.lambdaQuery(Article.class)
                .eq(Objects.nonNull(status) && !status.isBlank(), Article::getStatus, status)
                .eq(Objects.nonNull(authorId), Article::getAuthorId, authorId)
                .orderByDesc(Article::getId);
        if (Objects.nonNull(keyword) && !keyword.isBlank()) {
            // keyword 模糊匹配 title / summary
            wrapper.and(w -> w.like(Article::getTitle, keyword).or().like(Article::getSummary, keyword));
        }
        Page<Article> result = articleMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(articleService.assemble(result.getRecords()), result.getTotal(), page, size);
    }

    @Override
    public void updateTop(Long id, TopDTO dto) {
        assertFlagValid(dto.getIsTop());
        requireArticle(id);
        articleMapper.update(null, Wrappers.lambdaUpdate(Article.class)
                .set(Article::getIsTop, dto.getIsTop())
                .eq(Article::getId, id));
    }

    @Override
    public void updateRecommend(Long id, RecommendDTO dto) {
        assertFlagValid(dto.getIsRecommended());
        requireArticle(id);
        articleMapper.update(null, Wrappers.lambdaUpdate(Article.class)
                .set(Article::getIsRecommended, dto.getIsRecommended())
                .eq(Article::getId, id));
    }

    @Override
    public void offline(Long id) {
        requireArticle(id);
        articleMapper.update(null, Wrappers.lambdaUpdate(Article.class)
                .set(Article::getStatus, ArticleStatus.OFFLINE.name())
                .eq(Article::getId, id));
    }

    /**
     * 开关位仅允许 0/1
     */
    private void assertFlagValid(Short flag) {
        if (Objects.isNull(flag) || (flag != FLAG_OFF && flag != FLAG_ON)) {
            throw new BusinessException(StatusCode.PARAM_INVALID);
        }
    }

    private void requireArticle(Long id) {
        if (Objects.isNull(articleMapper.selectById(id))) {
            throw new BusinessException(StatusCode.ARTICLE_NOT_EXIST);
        }
    }
}
