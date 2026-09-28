package com.zer0drv.blog.interaction.service;

import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.common.response.PageResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 文章点赞 / 收藏、评论点赞、我的收藏。
 *
 * @author Yoruhaki
 */
public interface InteractionService {

    /**
     * 文章点赞（幂等：已赞则静默成功）
     */
    void likeArticle(Long articleId, Jwt jwt);

    /**
     * 取消文章点赞（物理删除关联）
     */
    void unlikeArticle(Long articleId, Jwt jwt);

    /**
     * 文章收藏（幂等）
     */
    void favoriteArticle(Long articleId, Jwt jwt);

    /**
     * 取消文章收藏（物理删除关联）
     */
    void unfavoriteArticle(Long articleId, Jwt jwt);

    /**
     * 评论点赞（幂等；维护 comment.like_count +1）
     */
    void likeComment(Long commentId, Jwt jwt);

    /**
     * 取消评论点赞（维护 comment.like_count -1）
     */
    void unlikeComment(Long commentId, Jwt jwt);

    /**
     * 我的收藏文章分页（按收藏时间倒序）
     */
    PageResult<ArticleListVO> pageMyFavorites(long page, long size, Jwt jwt);
}