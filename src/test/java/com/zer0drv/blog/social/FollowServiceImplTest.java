package com.zer0drv.blog.social;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.service.ArticleService;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.social.service.NotificationService;
import com.zer0drv.blog.social.service.impl.FollowServiceImpl;
import com.zer0drv.blog.social.vo.UserProfileVO;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.service.UserService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户主页纯单测：文章列表与 articleCount 均带定时发布可见性谓词
 * （status=PUBLISHED 且 publish_time &lt;= now），未上线定时文章不对外泄露。
 * MP 继承方法（count）用 spy + doReturn 桩掉，ArticleService 直接 mock 并捕获查询条件。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class FollowServiceImplTest {

    @Mock
    private UserService userService;
    @Mock
    private ArticleService articleService;
    @Mock
    private NotificationService notificationService;

    private FollowServiceImpl followService;

    @BeforeAll
    static void initTableInfo() {
        // 纯单测无 MyBatis 容器：手动初始化实体 TableInfo，供 LambdaQueryWrapper 解析列名
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Article.class);
    }

    @BeforeEach
    void setUp() {
        followService = spy(new FollowServiceImpl(userService, articleService, notificationService));
    }

    private static User userOf(long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        return user;
    }

    @Test
    void pageUserArticles_filtersScheduledArticles() {
        Page<Article> emptyPage = new Page<>(1, 10);
        when(articleService.page(any(Page.class), any())).thenReturn(emptyPage);
        when(articleService.assemble(any())).thenReturn(List.of());

        PageResult<ArticleListVO> result = followService.pageUserArticles(7L, 1, 10);

        assertEquals(0, result.getTotal());
        ArgumentCaptor<LambdaQueryWrapper<Article>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(articleService).page(any(Page.class), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        // 与全站其他列表一致：PUBLISHED 且 publish_time 已到（publish_time <= now 天然排除 NULL）
        assertTrue(sql.contains("status"), sql);
        assertTrue(sql.contains("publish_time") && sql.contains("<="), sql);
    }

    @Test
    void profile_articleCountFiltersScheduledArticles() {
        when(userService.getById(7L)).thenReturn(userOf(7L));
        // 粉丝/关注计数走 MP count，桩掉避免触库
        doReturn(0L).when(followService).count(any());
        when(articleService.count(any())).thenReturn(3L);

        UserProfileVO vo = followService.profile(7L, null);

        assertEquals(3L, vo.getArticleCount());
        ArgumentCaptor<LambdaQueryWrapper<Article>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(articleService).count(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("publish_time") && sql.contains("<="), sql);
    }
}
