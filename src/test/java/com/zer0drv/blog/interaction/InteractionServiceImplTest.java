package com.zer0drv.blog.interaction;

import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.enums.ArticleStatus;
import com.zer0drv.blog.article.service.ArticleService;
import com.zer0drv.blog.comment.domain.Comment;
import com.zer0drv.blog.comment.enums.CommentStatus;
import com.zer0drv.blog.comment.mapper.CommentMapper;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.interaction.domain.ArticleLike;
import com.zer0drv.blog.interaction.domain.CommentLike;
import com.zer0drv.blog.interaction.mapper.ArticleFavoriteMapper;
import com.zer0drv.blog.interaction.mapper.ArticleLikeMapper;
import com.zer0drv.blog.interaction.mapper.CommentLikeMapper;
import com.zer0drv.blog.interaction.service.impl.InteractionServiceImpl;
import com.zer0drv.blog.social.enums.NotificationType;
import com.zer0drv.blog.social.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 点赞/取消点赞纯单测：幂等静默、首次点赞发通知、草稿拒绝、计数扣减。
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
    private ArticleService articleService;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private InteractionServiceImpl interactionService;

    private static Jwt jwtOf(long userId) {
        return new Jwt("tk", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("alg", "HS256"),
                Map.of("sub", String.valueOf(userId), "roles", List.of("ROLE_USER")));
    }

    private static Article publishedArticle(long id, long authorId) {
        Article article = new Article();
        article.setId(id);
        article.setAuthorId(authorId);
        article.setTitle("hello");
        article.setStatus(ArticleStatus.PUBLISHED.name());
        // 可见性谓词要求 publish_time 非空且不晚于 now
        article.setPublishTime(LocalDateTime.now().minusMinutes(1));
        return article;
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
        when(articleService.getById(10L)).thenReturn(publishedArticle(10L, 1L));
        when(articleLikeMapper.selectCount(any())).thenReturn(0L);

        interactionService.likeArticle(10L, jwtOf(2L));

        verify(articleLikeMapper).insert(any(ArticleLike.class));
        verify(notificationService).notify(eq(1L), eq(NotificationType.ARTICLE_LIKE),
                eq(2L), eq(10L), isNull(), eq("hello"), eq(true));
    }

    @Test
    void likeArticle_repeat_isSilentAndSkipsNotification() {
        when(articleService.getById(10L)).thenReturn(publishedArticle(10L, 1L));
        when(articleLikeMapper.selectCount(any())).thenReturn(1L);

        interactionService.likeArticle(10L, jwtOf(2L));

        verify(articleLikeMapper, never()).insert(any(ArticleLike.class));
        verify(notificationService, never()).notify(any(), any(), any(), any(), any(), any(), eq(true));
    }

    @Test
    void likeArticle_concurrentDuplicate_isSilentAndSkipsNotification() {
        when(articleService.getById(10L)).thenReturn(publishedArticle(10L, 1L));
        when(articleLikeMapper.selectCount(any())).thenReturn(0L);
        doThrow(new DuplicateKeyException("uk")).when(articleLikeMapper).insert(any(ArticleLike.class));

        // 唯一索引兜底：不抛异常、不重复发通知
        interactionService.likeArticle(10L, jwtOf(2L));

        verify(notificationService, never()).notify(any(), any(), any(), any(), any(), any(), eq(true));
    }

    @Test
    void likeArticle_draftArticle_rejected() {
        Article draft = publishedArticle(10L, 1L);
        draft.setStatus(ArticleStatus.DRAFT.name());
        when(articleService.getById(10L)).thenReturn(draft);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> interactionService.likeArticle(10L, jwtOf(2L)));
        assertEquals(StatusCode.ARTICLE_NOT_EXIST.getCode(), ex.getCode());
        verify(articleLikeMapper, never()).insert(any(ArticleLike.class));
    }

    @Test
    void likeArticle_missingArticle_rejected() {
        when(articleService.getById(10L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> interactionService.likeArticle(10L, jwtOf(2L)));
        assertEquals(StatusCode.ARTICLE_NOT_EXIST.getCode(), ex.getCode());
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
        verify(notificationService).notify(eq(1L), eq(NotificationType.COMMENT_LIKE),
                eq(2L), eq(10L), eq(100L), eq("nice"), eq(true));
    }

    @Test
    void likeComment_repeat_isSilentWithoutCountChange() {
        Comment comment = normalComment(100L, 1L, 10L);
        when(commentMapper.selectById(100L)).thenReturn(comment);
        when(commentLikeMapper.selectCount(any())).thenReturn(1L);

        interactionService.likeComment(100L, jwtOf(2L));

        verify(commentLikeMapper, never()).insert(any(CommentLike.class));
        verify(commentMapper, never()).update(any(), any());
        verify(notificationService, never()).notify(any(), any(), any(), any(), any(), any(), eq(true));
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
