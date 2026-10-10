package com.zer0drv.blog.social;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.zer0drv.blog.article.api.ArticleCatalog;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.social.domain.Follow;
import com.zer0drv.blog.social.service.NotificationService;
import com.zer0drv.blog.social.service.impl.FollowServiceImpl;
import com.zer0drv.blog.social.vo.UserProfileVO;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 用户主页 / 关注流纯单测：可见性谓词与列表装配已收口到 article.api.ArticleCatalog，
 * 本类只断言「转发到 catalog 的参数与透传结果」，谓词 SQL 断言由 ArticleCatalogImplTest 边界测试替代。
 * ArticleCatalog 不含 MP 类型，不再需要 TableInfoHelper 初始化样板。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class FollowServiceImplTest {

    @Mock
    private UserService userService;
    @Mock
    private ArticleCatalog articleCatalog;
    @Mock
    private NotificationService notificationService;

    private FollowServiceImpl followService;

    @BeforeEach
    void setUp() {
        followService = spy(new FollowServiceImpl(userService, articleCatalog, notificationService));
    }

    private static User userOf(long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        return user;
    }

    private static Follow followOf(long followerId, long followeeId) {
        Follow follow = new Follow();
        follow.setFollowerId(followerId);
        follow.setFolloweeId(followeeId);
        return follow;
    }

    private static Jwt jwtOf(long userId) {
        return new Jwt("tk", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("alg", "HS256"),
                Map.of("sub", String.valueOf(userId), "roles", List.of("ROLE_USER")));
    }

    @Test
    void pageUserArticles_delegatesToCatalog() {
        PageResult<ArticleListVO> catalogResult = PageResult.of(List.of(new ArticleListVO()), 1, 2, 10);
        when(articleCatalog.pageVisibleByAuthors(List.of(7L), 2, 10)).thenReturn(catalogResult);

        PageResult<ArticleListVO> result = followService.pageUserArticles(7L, 2, 10);

        // 结果原样透传（谓词 / 排序 / 装配都在 catalog 内）
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals(2, result.getPage());
    }

    @Test
    void pageFeed_emptyFollowees_returnsEmptyPageWithoutCatalog() {
        doReturn(List.of()).when(followService).list(any(Wrapper.class));

        PageResult<ArticleListVO> result = followService.pageFeed(1, 10, jwtOf(1L));

        assertEquals(0, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
        verifyNoInteractions(articleCatalog);
    }

    @Test
    void pageFeed_withFollowees_delegatesToCatalog() {
        doReturn(List.of(followOf(1L, 8L), followOf(1L, 9L))).when(followService).list(any(Wrapper.class));
        PageResult<ArticleListVO> catalogResult = PageResult.of(List.of(new ArticleListVO()), 1, 1, 10);
        when(articleCatalog.pageVisibleByAuthors(List.of(8L, 9L), 1, 10)).thenReturn(catalogResult);

        PageResult<ArticleListVO> result = followService.pageFeed(1, 10, jwtOf(1L));

        assertEquals(1, result.getTotal());
        // 关注人集合原样传给 catalog
        verify(articleCatalog).pageVisibleByAuthors(List.of(8L, 9L), 1, 10);
    }

    @Test
    void profile_articleCountFromCatalog() {
        when(userService.getById(7L)).thenReturn(userOf(7L));
        // 粉丝/关注计数走 MP count，桩掉避免触库
        doReturn(0L).when(followService).count(any());
        when(articleCatalog.countVisibleByAuthor(7L)).thenReturn(3L);

        UserProfileVO vo = followService.profile(7L, null);

        assertEquals(3L, vo.getArticleCount());
        verify(articleCatalog).countVisibleByAuthor(7L);
    }
}
