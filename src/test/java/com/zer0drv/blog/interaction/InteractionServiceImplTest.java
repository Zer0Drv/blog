package com.zer0drv.blog.interaction;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zer0drv.blog.article.api.ArticleCatalog;
import com.zer0drv.blog.article.api.ArticleRef;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.comment.domain.Comment;
import com.zer0drv.blog.comment.enums.CommentStatus;
import com.zer0drv.blog.comment.mapper.CommentMapper;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.interaction.domain.ArticleFavorite;
import com.zer0drv.blog.interaction.domain.ArticleLike;
import com.zer0drv.blog.interaction.domain.CommentLike;
import com.zer0drv.blog.interaction.mapper.ArticleFavoriteMapper;
import com.zer0drv.blog.interaction.mapper.ArticleLikeMapper;
import com.zer0drv.blog.interaction.mapper.CommentLikeMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 点赞/取消点赞纯单测：幂等静默、首次点赞发通知、可见性拒绝、计数扣减。
 * 文章可见性判定已收口到 article.api.ArticleCatalog（requireVisible 抛 ARTICLE_NOT_EXIST），
 * 「草稿 / 定时中 / 不存在为何不可见」的谓词细节由 ArticleCatalogImplTest 边界测试覆盖，此处不叠加。
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
    private CommentLikeMapper commentLikeMapper;
    @Mock
    private CommentMapper commentMapper;
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

    private static Comment normalComment(long id, long userId, long articleId) {
        Comment comment = new Comment();
        comment.setId(id);
        comment.setUserId(userId);
        comment.setArticleId(articleId);
        comment.setStatus(CommentStatus.NORMAL.name());
        return comment;
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
    void likeComment_firstTime_incrementsCountAndNotifies() {
        Comment comment = normalComment(100L, 1L, 10L);
        comment.setContent("nice");
        when(commentMapper.selectById(100L)).thenReturn(comment);
        when(commentLikeMapper.selectCount(any())).thenReturn(0L);

        interactionService.likeComment(100L, jwtOf(2L));

        verify(commentLikeMapper).insert(any(CommentLike.class));
        // like_count 原子自增
        verify(commentMapper).update(isNull(), any());
        verify(notificationService).notify(NotificationIntent.commentLike(1L, 2L, 10L, 100L, "nice"));
    }

    @Test
    void likeComment_repeat_isSilentWithoutCountChange() {
        Comment comment = normalComment(100L, 1L, 10L);
        when(commentMapper.selectById(100L)).thenReturn(comment);
        when(commentLikeMapper.selectCount(any())).thenReturn(1L);

        interactionService.likeComment(100L, jwtOf(2L));

        verify(commentLikeMapper, never()).insert(any(CommentLike.class));
        verify(commentMapper, never()).update(any(), any());
        verify(notificationService, never()).notify(any());
    }

    @Test
    void likeComment_missingComment_rejected() {
        when(commentMapper.selectById(100L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> interactionService.likeComment(100L, jwtOf(2L)));
        assertEquals(StatusCode.COMMENT_NOT_EXIST.getCode(), ex.getCode());
    }

    @Test
    void likeComment_foldedComment_rejected() {
        // 仅 NORMAL 评论可点赞：FOLDED 评论对外不可见
        Comment folded = normalComment(100L, 1L, 10L);
        folded.setStatus(CommentStatus.FOLDED.name());
        when(commentMapper.selectById(100L)).thenReturn(folded);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> interactionService.likeComment(100L, jwtOf(2L)));
        assertEquals(StatusCode.COMMENT_NOT_EXIST.getCode(), ex.getCode());
        verify(commentLikeMapper, never()).insert(any(CommentLike.class));
    }

    @Test
    void likeComment_pendingComment_rejected() {
        // 仅 NORMAL 评论可点赞：PENDING（待审核）评论对外不可见
        Comment pending = normalComment(100L, 1L, 10L);
        pending.setStatus(CommentStatus.PENDING.name());
        when(commentMapper.selectById(100L)).thenReturn(pending);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> interactionService.likeComment(100L, jwtOf(2L)));
        assertEquals(StatusCode.COMMENT_NOT_EXIST.getCode(), ex.getCode());
        verify(commentLikeMapper, never()).insert(any(CommentLike.class));
    }

    // ---------- 取消点赞 ----------

    @Test
    void unlikeComment_existingLike_decrementsCount() {
        when(commentLikeMapper.delete(any())).thenReturn(1);

        interactionService.unlikeComment(100L, jwtOf(2L));

        // 确实删到一条才扣减计数
        verify(commentMapper).update(isNull(), any());
    }

    @Test
    void unlikeComment_noLike_doesNotTouchCount() {
        when(commentLikeMapper.delete(any())).thenReturn(0);

        interactionService.unlikeComment(100L, jwtOf(2L));

        verify(commentMapper, never()).update(any(), any());
    }

    @Test
    void unlikeArticle_deletesLikeRow() {
        interactionService.unlikeArticle(10L, jwtOf(2L));

        verify(articleLikeMapper).delete(any());
    }
}
