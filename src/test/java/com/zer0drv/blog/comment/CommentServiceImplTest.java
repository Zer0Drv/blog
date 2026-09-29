package com.zer0drv.blog.comment;

import com.zer0drv.blog.admin.service.SensitiveWordService;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.enums.ArticleStatus;
import com.zer0drv.blog.article.mapper.ArticleMapper;
import com.zer0drv.blog.comment.domain.Comment;
import com.zer0drv.blog.comment.dto.CommentCreateDTO;
import com.zer0drv.blog.comment.enums.CommentStatus;
import com.zer0drv.blog.comment.service.impl.CommentServiceImpl;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.interaction.mapper.CommentLikeMapper;
import com.zer0drv.blog.social.enums.NotificationType;
import com.zer0drv.blog.social.service.NotificationService;
import com.zer0drv.blog.user.service.UserService;
import io.github.linpeilie.Converter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评论创建/删除纯单测：敏感词折叠、二级回复归一化、删除连带回复。
 * MP 继承方法（save/getById/removeById/remove）用 spy + doReturn 桩掉。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class CommentServiceImplTest {

    @Mock
    private ArticleMapper articleMapper;
    @Mock
    private CommentLikeMapper commentLikeMapper;
    @Mock
    private UserService userService;
    @Mock
    private Converter converter;
    @Mock
    private NotificationService notificationService;
    @Mock
    private SensitiveWordService sensitiveWordService;

    private CommentServiceImpl commentService;

    @BeforeEach
    void setUp() {
        commentService = spy(new CommentServiceImpl(
                articleMapper, commentLikeMapper, userService, converter,
                notificationService, sensitiveWordService));
    }

    private static Jwt jwtOf(long userId, String... roles) {
        return new Jwt("tk", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("alg", "HS256"),
                Map.of("sub", String.valueOf(userId), "roles", List.of(roles)));
    }

    private static Article publishedArticle(long id, long authorId) {
        Article article = new Article();
        article.setId(id);
        article.setAuthorId(authorId);
        article.setStatus(ArticleStatus.PUBLISHED.name());
        return article;
    }

    private static CommentCreateDTO dtoOf(long articleId, String content, Long parentId) {
        CommentCreateDTO dto = new CommentCreateDTO();
        dto.setArticleId(articleId);
        dto.setContent(content);
        dto.setParentId(parentId);
        return dto;
    }

    @Test
    void create_sensitiveHit_savedAsFoldedWithoutNotification() {
        when(articleMapper.selectById(10L)).thenReturn(publishedArticle(10L, 1L));
        when(sensitiveWordService.containsSensitiveWord("bad word here")).thenReturn(true);
        doReturn(true).when(commentService).save(any(Comment.class));

        commentService.create(dtoOf(10L, "bad word here", null), jwtOf(2L));

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentService).save(captor.capture());
        assertEquals(CommentStatus.FOLDED.name(), captor.getValue().getStatus());
        // 命中敏感词：不触发任何通知
        verify(notificationService, never()).notify(anyLong(), any(), anyLong(),
                any(), any(), any(), eq(false));
    }

    @Test
    void create_rootComment_savedNormalAndNotifiesAuthor() {
        when(articleMapper.selectById(10L)).thenReturn(publishedArticle(10L, 1L));
        when(sensitiveWordService.containsSensitiveWord(any())).thenReturn(false);
        doReturn(true).when(commentService).save(any(Comment.class));

        commentService.create(dtoOf(10L, "great article", null), jwtOf(2L));

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentService).save(captor.capture());
        Comment saved = captor.getValue();
        assertEquals(CommentStatus.NORMAL.name(), saved.getStatus());
        assertEquals(0L, saved.getParentId());
        assertEquals(2L, saved.getUserId());
        // 主评论 → 文章作者收 COMMENT_REPLY（不做 dedupe）
        verify(notificationService).notify(eq(1L), eq(NotificationType.COMMENT_REPLY),
                eq(2L), eq(10L), isNull(), eq("great article"), eq(false));
    }

    @Test
    void create_replyToSecondLevel_normalizesToRoot() {
        when(articleMapper.selectById(10L)).thenReturn(publishedArticle(10L, 1L));
        when(sensitiveWordService.containsSensitiveWord(any())).thenReturn(false);
        doReturn(true).when(commentService).save(any(Comment.class));
        // parent=5 是二级评论（其父为 root=2），同属文章 10
        Comment parent = new Comment();
        parent.setId(5L);
        parent.setParentId(2L);
        parent.setArticleId(10L);
        parent.setUserId(8L);
        doReturn(parent).when(commentService).getById(5L);
        // notifyCommentCreated 内部再查 root=2
        Comment root = new Comment();
        root.setId(2L);
        root.setParentId(0L);
        root.setUserId(8L);
        doReturn(root).when(commentService).getById(2L);

        CommentCreateDTO dto = dtoOf(10L, "reply to a reply", 5L);
        dto.setReplyToUserId(9L);
        commentService.create(dto, jwtOf(2L));

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentService).save(captor.capture());
        Comment saved = captor.getValue();
        // 严格两层：parentId 归一化到 root=2，replyToUserId 保留传入值
        assertEquals(2L, saved.getParentId());
        assertEquals(9L, saved.getReplyToUserId());
    }

    @Test
    void create_replyToMissingParent_rejected() {
        when(articleMapper.selectById(10L)).thenReturn(publishedArticle(10L, 1L));
        when(sensitiveWordService.containsSensitiveWord(any())).thenReturn(false);
        doReturn(null).when(commentService).getById(99L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> commentService.create(dtoOf(10L, "hi", 99L), jwtOf(2L)));
        assertEquals(StatusCode.COMMENT_NOT_EXIST.getCode(), ex.getCode());
        verify(commentService, never()).save(any());
    }

    @Test
    void create_onUnpublishedArticle_rejected() {
        Article draft = publishedArticle(10L, 1L);
        draft.setStatus(ArticleStatus.DRAFT.name());
        when(articleMapper.selectById(10L)).thenReturn(draft);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> commentService.create(dtoOf(10L, "hi", null), jwtOf(2L)));
        assertEquals(StatusCode.ARTICLE_NOT_PUBLISHED.getCode(), ex.getCode());
        verify(commentService, never()).save(any());
    }

    @Test
    void delete_rootComment_cascadesReplies() {
        Comment root = new Comment();
        root.setId(20L);
        root.setParentId(0L);
        root.setUserId(3L);
        doReturn(root).when(commentService).getById(20L);
        doReturn(true).when(commentService).removeById(20L);
        doReturn(true).when(commentService).remove(any());

        commentService.delete(20L, jwtOf(3L, "ROLE_USER"));

        verify(commentService).removeById(20L);
        // 主评论删除连带逻辑删除其全部回复
        verify(commentService).remove(any());
    }

    @Test
    void delete_replyComment_doesNotCascade() {
        Comment reply = new Comment();
        reply.setId(21L);
        reply.setParentId(20L);
        reply.setUserId(3L);
        doReturn(reply).when(commentService).getById(21L);
        doReturn(true).when(commentService).removeById(21L);

        commentService.delete(21L, jwtOf(3L, "ROLE_USER"));

        verify(commentService).removeById(21L);
        verify(commentService, never()).remove(any());
    }

    @Test
    void delete_othersCommentAsNonAdmin_rejected() {
        Comment comment = new Comment();
        comment.setId(20L);
        comment.setParentId(0L);
        comment.setUserId(9L);
        doReturn(comment).when(commentService).getById(20L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> commentService.delete(20L, jwtOf(3L, "ROLE_USER")));
        assertEquals(StatusCode.NOT_AUTHOR.getCode(), ex.getCode());
        verify(commentService, never()).removeById(any(java.io.Serializable.class));
    }

    @Test
    void delete_othersCommentAsAdmin_allowed() {
        Comment comment = new Comment();
        comment.setId(20L);
        comment.setParentId(0L);
        comment.setUserId(9L);
        doReturn(comment).when(commentService).getById(20L);
        doReturn(true).when(commentService).removeById(20L);
        doReturn(true).when(commentService).remove(any());

        commentService.delete(20L, jwtOf(3L, "ROLE_ADMIN"));

        verify(commentService).removeById(20L);
    }
}
