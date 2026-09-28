package com.zer0drv.blog.social.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.social.domain.Follow;
import com.zer0drv.blog.social.vo.FollowUserVO;
import com.zer0drv.blog.social.vo.UserProfileVO;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 关注 + 关注动态 Feed
 *
 * @author Yoruhaki
 */
public interface FollowService extends IService<Follow> {

    /**
     * 关注（幂等；不能关注自己 FOLLOW_SELF_INVALID）
     */
    void follow(Long id, Jwt jwt);

    /**
     * 取关（物理删除；未关注时报 USER_NOT_FOLLOWED）
     */
    void unfollow(Long id, Jwt jwt);

    /**
     * 粉丝分页（公开）
     */
    PageResult<FollowUserVO> pageFollowers(Long userId, long page, long size, Jwt jwt);

    /**
     * 关注分页（公开）
     */
    PageResult<FollowUserVO> pageFollowing(Long userId, long page, long size, Jwt jwt);

    /**
     * 用户公开主页信息（公开）
     */
    UserProfileVO profile(Long userId, Jwt jwt);

    /**
     * 关注动态：关注作者的 PUBLISHED 文章分页（publish_time 倒序；未关注任何人返回空页）
     */
    PageResult<ArticleListVO> pageFeed(long page, long size, Jwt jwt);

    /**
     * 某用户的 PUBLISHED 文章分页（公开，publish_time 倒序）
     */
    PageResult<ArticleListVO> pageUserArticles(Long userId, long page, long size);
}
