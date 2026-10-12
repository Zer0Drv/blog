package com.zer0drv.blog.interaction;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zer0drv.blog.article.api.ArticleCatalog;
import com.zer0drv.blog.article.api.ArticleRef;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.comment.api.CommentLikePort;
import com.zer0drv.blog.comment.api.CommentRef;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.interaction.domain.ArticleFavorite;
import com.zer0drv.blog.interaction.domain.ArticleLike;
import com.zer0drv.blog.interaction.mapper.ArticleFavoriteMapper;
import com.zer0drv.blog.interaction.mapper.ArticleLikeMapper;
import com.zer0drv.blog.interaction.service.impl.InteractionServiceImpl;
import com.zer0drv.blog.social.service.NotificationIntent;
import com.zer0drv.blog.social.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 点赞/取消点赞纯单测：幂等静默、首次点赞发通知、可见性拒绝。
 * 文章可见性判定已收口到 article.api.ArticleCatalog（requireVisible 抛 ARTICLE_NOT_EXIST），
 * 评论数据访问已收口到 comment.api.CommentLikePort（issue #34 第一步）——
 * 「仅 NORMAL 可点赞 / like_count 原子 ±1 / 删到关联才扣减」的口径细节由
 * CommentLikePortImplTest 覆盖，此处只验证编排与通知。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class InteractionServiceImplTest {

    @Mock
    private ArticleLikeMapper articleLikeMapper;
    @Mock
    private ArticleFavoriteMapper articleFavoriteMapper;
    @Mock
    private CommentLikePort commentLikePort;
    @Mock
    private ArticleCatalog articleCatalog;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private InteractionServiceImpl interactionService;

    private static Jwt jwtOf(long userId) {
        return new Jwt("tk", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("alg", "HS256"),
                Map.of("sub", String.valueOf(userId), "roles", List.of("ROLE_USER")));
    }

    // ---------- 文章点赞 ----------

    @Test
    void likeArticle_firstTime_insertsAndNotifiesAuthor() {
        when(articleCatalog.requireVisible(10L)).thenReturn(new ArticleRef(10L, 1L, "hello"));
        when(articleLikeMapper.selectCount(any())).thenReturn(0L);

        interactionService.likeArticle(10L, jwtOf(2L));

        verify(articleLikeMapper).insert(any(ArticleLike.class));
        verify(notificationService).notify(NotificationIntent.articleLike(1L, 2L, 10L, "hello"));
    }

    @Test
    void likeArticle_repeat_isSilentAndSkipsNotification() {
        when(articleCatalog.requireVisible(10L)).thenReturn(new ArticleRef(10L, 1L, "hello"));
        when(articleLikeMapper.selectCount(any())).thenReturn(1L);

        interactionService.likeArticle(10L, jwtOf(2L));

        verify(articleLikeMapper, never()).insert(any(ArticleLike.class));
        verify(notificationService, never()).notify(any());
    }

    @Test
    void likeArticle_concurrentDuplicate_isSilentAndSkipsNotification() {
        when(articleCatalog.requireVisible(10L)).thenReturn(new ArticleRef(10L, 1L, "hello"));
        when(articleLikeMapper.selectCount(any())).thenReturn(0L);
        doThrow(new DuplicateKeyException("uk")).when(articleLikeMapper).insert(any(ArticleLike.class));

        // 唯一索引兜底：不抛异常、不重复发通知
        interactionService.likeArticle(10L, jwtOf(2L));

        verify(notificationService, never()).notify(any());
    }

    @Test
    void likeArticle_invisibleArticle_rejected() {
        // 不存在 / 草稿 / 下架 / 定时中：catalog 一律以 ARTICLE_NOT_EXIST 抛出（不暴露存在性）
        when(articleCatalog.requireVisible(10L))
                .thenThrow(new BusinessException(StatusCode.ARTICLE_NOT_EXIST));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> interactionService.likeArticle(10L, jwtOf(2L)));
        assertEquals(StatusCode.ARTICLE_NOT_EXIST.getCode(), ex.getCode());
        verify(articleLikeMapper, never()).insert(any(ArticleLike.class));
    }

    @Test
    void favoriteArticle_invisibleArticle_rejected() {
        when(articleCatalog.requireVisible(10L))
                .thenThrow(new BusinessException(StatusCode.ARTICLE_NOT_EXIST));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> interactionService.favoriteArticle(10L, jwtOf(2L)));
        assertEquals(StatusCode.ARTICLE_NOT_EXIST.getCode(), ex.getCode());
        verify(articleFavoriteMapper, never()).insert(any(ArticleFavorite.class));
    }

    // ---------- 我的收藏 ----------

    @Test
    void pageMyFavorites_delegatesToCatalogPreservingOrderAndTotal() {
        // 收藏分页返回 [2, 1]（收藏时间倒序），total=5 含已删文章的收藏记录
        Page<ArticleFavorite> favoritePage = new Page<>(1, 10);
        ArticleFavorite f1 = new ArticleFavorite();
        f1.setArticleId(2L);
        ArticleFavorite f2 = new ArticleFavorite();
        f2.setArticleId(1L);
        favoritePage.setRecords(List.of(f1, f2));
        favoritePage.setTotal(5);
        when(articleFavoriteMapper.selectPage(any(), any())).thenReturn(favoritePage);
        ArticleListVO vo2 = new ArticleListVO();
        vo2.setId(2L);
        ArticleListVO vo1 = new ArticleListVO();
        vo1.setId(1L);
        when(articleCatalog.listByIdsPreserveOrder(List.of(2L, 1L))).thenReturn(List.of(vo2, vo1));

        PageResult<ArticleListVO> result = interactionService.pageMyFavorites(1, 10, jwtOf(3L));

        // 记录顺序保持收藏顺序；total 仍是收藏总数（不被已删过滤改写）
        assertEquals(List.of(2L, 1L), result.getRecords().stream().map(ArticleListVO::getId).toList());
        assertEquals(5, result.getTotal());
    }

    // ---------- 评论点赞 ----------

    @Test
    void likeComment_firstTime_addsLikeAndNotifies() {
        when(commentLikePort.findLikeable(100L))
                .thenReturn(Optional.of(new CommentRef(100L, 1L, 10L, "nice")));
        when(commentLikePort.addLike(100L, 2L)).thenReturn(true);

        interactionService.likeComment(100L, jwtOf(2L));

        verify(commentLikePort).addLike(100L, 2L);
        verify(notificationService).notify(NotificationIntent.commentLike(1L, 2L, 10L, 100L, "nice"));
    }

    @Test
    void likeComment_repeat_isSilentAndSkipsNotification() {
        when(commentLikePort.findLikeable(100L))
                .thenReturn(Optional.of(new CommentRef(100L, 1L, 10L, "nice")));
        // 已赞过（或并发重复被唯一索引兜底）：端口返回 false
        when(commentLikePort.addLike(100L, 2L)).thenReturn(false);

        interactionService.likeComment(100L, jwtOf(2L));

        verify(notificationService, never()).notify(any());
    }

    @Test
    void likeComment_missingComment_rejected() {
        when(commentLikePort.findLikeable(100L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> interactionService.likeComment(100L, jwtOf(2L)));
        assertEquals(StatusCode.COMMENT_NOT_EXIST.getCode(), ex.getCode());
        verify(commentLikePort, never()).addLike(anyLong(), anyLong());
    }

    @Test
    void likeComment_hiddenComment_rejected() {
        // FOLDED / PENDING 评论对外不可见：端口查询侧即过滤，此处同样表现为「不存在」
        when(commentLikePort.findLikeable(100L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> interactionService.likeComment(100L, jwtOf(2L)));
        assertEquals(StatusCode.COMMENT_NOT_EXIST.getCode(), ex.getCode());
        verify(commentLikePort, never()).addLike(anyLong(), anyLong());
        verify(notificationService, never()).notify(any());
    }

    @Test
    void likeComment_longContent_notifiesWithFiftyCharSummary() {
        String content = "x".repeat(60);
        when(commentLikePort.findLikeable(100L))
                .thenReturn(Optional.of(new CommentRef(100L, 1L, 10L, content)));
        when(commentLikePort.addLike(100L, 2L)).thenReturn(true);

        interactionService.likeComment(100L, jwtOf(2L));

        verify(notificationService).notify(
                NotificationIntent.commentLike(1L, 2L, 10L, 100L, "x".repeat(50)));
    }

    // ---------- 取消点赞 ----------

    @Test
    void unlikeComment_delegatesToPort() {
        interactionService.unlikeComment(100L, jwtOf(2L));

        // 删关联与「确实删到才扣计数」的语义均在端口实现内
        verify(commentLikePort).removeLike(100L, 2L);
    }

    @Test
    void unlikeArticle_deletesLikeRow() {
        interactionService.unlikeArticle(10L, jwtOf(2L));

        verify(articleLikeMapper).delete(any());
    }
}
