package com.zer0drv.blog.comment;

import com.zer0drv.blog.article.api.ArticleCatalog;
import com.zer0drv.blog.article.api.ArticleRef;
import com.zer0drv.blog.comment.domain.Comment;
import com.zer0drv.blog.comment.dto.CommentCreateDTO;
import com.zer0drv.blog.comment.enums.CommentStatus;
import com.zer0drv.blog.comment.service.CommentCreateResult;
import com.zer0drv.blog.comment.service.impl.CommentServiceImpl;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.common.sensitive.SensitiveWordChecker;
import com.zer0drv.blog.interaction.mapper.CommentLikeMapper;
import com.zer0drv.blog.site.service.SiteConfigService;
import com.zer0drv.blog.social.service.NotificationIntent;
import com.zer0drv.blog.social.service.NotificationService;
import com.zer0drv.blog.user.service.UserService;
import io.github.linpeilie.Converter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评论创建/删除纯单测：敏感词折叠、P0 审核队列（PENDING）、文章可见性前置校验（双错误码）、
 * 二级回复归一化、删除连带回复。
 * 文章侧只 mock article.api.ArticleCatalog（无 MP 类型，无需 TableInfoHelper 样板）；
 * 「草稿 / 定时中 / publish_time 为空为何不可见」的谓词细节由 ArticleVisibility /
 * ArticleCatalogImpl 边界测试覆盖，此处不叠加。MP 继承方法（save/getById/removeById/remove）
 * 用 spy + doReturn 桩掉。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class CommentServiceImplTest {

    @Mock
    private ArticleCatalog articleCatalog;
    @Mock
    private CommentLikeMapper commentLikeMapper;
    @Mock
    private UserService userService;
    @Mock
    private Converter converter;
    @Mock
    private NotificationService notificationService;
    @Mock
    private SensitiveWordChecker sensitiveWordChecker;
    @Mock
    private ObjectProvider<SiteConfigService> siteConfigServiceProvider;
    @Mock
    private SiteConfigService siteConfigService;

    private CommentServiceImpl commentService;

    @BeforeEach
    void setUp() {
        commentService = spy(new CommentServiceImpl(
                articleCatalog, commentLikeMapper, userService, converter,
                notificationService, sensitiveWordChecker, siteConfigServiceProvider));
        // 默认：SiteConfigService 可用但审核开关关闭（非 create 路径的测试不触达该桩，lenient 防误报）
        lenient().when(siteConfigServiceProvider.getIfAvailable()).thenReturn(siteConfigService);
        lenient().when(siteConfigService.getBool(anyString(), anyBoolean())).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        // jwtOf 会向安全上下文写入认证，逐个用例清理防串扰
        SecurityContextHolder.clearContext();
    }

    private static Jwt jwtOf(long userId, String... roles) {
        Jwt jwt = new Jwt("tk", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("alg", "HS256"),
                Map.of("sub", String.valueOf(userId), "roles", List.of(roles)));
        // 与生产链路对齐：isAdmin 以安全上下文权限为准（由 DatabaseRoleJwtAuthenticationConverter
        // 实时读库装配），单测在此把 roles 同步进安全上下文
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
        return jwt;
    }

    /**
     * 存在且可见的文章（通过 create 前置校验）
     */
    private void stubVisibleArticle(long id, long authorId) {
        when(articleCatalog.findRef(id)).thenReturn(Optional.of(new ArticleRef(id, authorId, "标题")));
        when(articleCatalog.isVisible(id)).thenReturn(true);
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
        stubVisibleArticle(10L, 1L);
        when(sensitiveWordChecker.containsSensitiveWord("bad word here")).thenReturn(true);
        doReturn(true).when(commentService).save(any(Comment.class));

        CommentCreateResult result = commentService.create(dtoOf(10L, "bad word here", null), jwtOf(2L));

        assertEquals(CommentStatus.FOLDED, result.status());
        // 每条评论的敏感词判定只跑一次（结论随行返回，controller 不再二次判定）
        verify(sensitiveWordChecker, times(1)).containsSensitiveWord(any());
        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentService).save(captor.capture());
        assertEquals(CommentStatus.FOLDED.name(), captor.getValue().getStatus());
        // 命中敏感词：不触发任何通知
        verify(notificationService, never()).notify(any());
    }

    @Test
    void create_rootComment_savedNormalAndNotifiesAuthor() {
        stubVisibleArticle(10L, 1L);
        when(sensitiveWordChecker.containsSensitiveWord(any())).thenReturn(false);
        doReturn(true).when(commentService).save(any(Comment.class));

        CommentCreateResult result = commentService.create(dtoOf(10L, "great article", null), jwtOf(2L));

        assertEquals(CommentStatus.NORMAL, result.status());
        verify(sensitiveWordChecker, times(1)).containsSensitiveWord(any());
        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentService).save(captor.capture());
        Comment saved = captor.getValue();
        assertEquals(CommentStatus.NORMAL.name(), saved.getStatus());
        assertEquals(0L, saved.getParentId());
        assertEquals(2L, saved.getUserId());
        // 主评论 → 文章作者收 COMMENT_REPLY（不做 dedupe）
        verify(notificationService).notify(NotificationIntent.commentReply(1L, 2L, 10L, null, "great article"));
    }

    @Test
    void create_replyToSecondLevel_normalizesToRoot() {
        stubVisibleArticle(10L, 1L);
        when(sensitiveWordChecker.containsSensitiveWord(any())).thenReturn(false);
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
        // 用户 9 在楼层 2 内已有 NORMAL 回复 → 是合法参与者
        doReturn(1L).when(commentService).count(any());

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
    void create_replyToRootAuthor_allowed() {
        stubVisibleArticle(10L, 1L);
        when(sensitiveWordChecker.containsSensitiveWord(any())).thenReturn(false);
        doReturn(true).when(commentService).save(any(Comment.class));
        // parent=5 是主评论（root），作者为 8
        Comment root = new Comment();
        root.setId(5L);
        root.setParentId(0L);
        root.setArticleId(10L);
        root.setUserId(8L);
        doReturn(root).when(commentService).getById(5L);

        CommentCreateDTO dto = dtoOf(10L, "reply to root author", 5L);
        dto.setReplyToUserId(8L);
        commentService.create(dto, jwtOf(2L));

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentService).save(captor.capture());
        Comment saved = captor.getValue();
        assertEquals(5L, saved.getParentId());
        assertEquals(8L, saved.getReplyToUserId());
        // root 作者即被 @ 人：只发 MENTION，不重复发 COMMENT_REPLY（全程仅这一次通知）
        verify(notificationService).notify(NotificationIntent.mention(8L, 2L, 10L, null, "reply to root author"));
        verify(notificationService, times(1)).notify(any());
    }

    @Test
    void create_replyToNonParticipant_rejected() {
        // replyToUserId 不是 root 作者、也未在楼层内回复过 → 参数无效，防 MENTION 轰炸
        stubVisibleArticle(10L, 1L);
        when(sensitiveWordChecker.containsSensitiveWord(any())).thenReturn(false);
        Comment root = new Comment();
        root.setId(5L);
        root.setParentId(0L);
        root.setArticleId(10L);
        root.setUserId(8L);
        doReturn(root).when(commentService).getById(5L);
        doReturn(0L).when(commentService).count(any());

        CommentCreateDTO dto = dtoOf(10L, "ping stranger", 5L);
        dto.setReplyToUserId(99L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> commentService.create(dto, jwtOf(2L)));
        assertEquals(StatusCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(commentService, never()).save(any());
        verify(notificationService, never()).notify(any());
    }

    @Test
    void create_replyWithoutReplyToUser_notifiesRootAuthor() {
        stubVisibleArticle(10L, 1L);
        when(sensitiveWordChecker.containsSensitiveWord(any())).thenReturn(false);
        doReturn(true).when(commentService).save(any(Comment.class));
        Comment root = new Comment();
        root.setId(5L);
        root.setParentId(0L);
        root.setArticleId(10L);
        root.setUserId(8L);
        doReturn(root).when(commentService).getById(5L);

        commentService.create(dtoOf(10L, "plain reply", 5L), jwtOf(2L));

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentService).save(captor.capture());
        assertEquals(5L, captor.getValue().getParentId());
        assertEquals(null, captor.getValue().getReplyToUserId());
        // replyToUserId 为空 → root 作者收 COMMENT_REPLY，无 MENTION（全程仅这一次通知）
        verify(notificationService).notify(NotificationIntent.commentReply(8L, 2L, 10L, null, "plain reply"));
        verify(notificationService, times(1)).notify(any());
    }

    @Test
    void create_replyToMissingParent_rejected() {
        stubVisibleArticle(10L, 1L);
        when(sensitiveWordChecker.containsSensitiveWord(any())).thenReturn(false);
        doReturn(null).when(commentService).getById(99L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> commentService.create(dtoOf(10L, "hi", 99L), jwtOf(2L)));
        assertEquals(StatusCode.COMMENT_NOT_EXIST.getCode(), ex.getCode());
        verify(commentService, never()).save(any());
    }

    @Test
    void create_onMissingArticle_rejectedAsNotExist() {
        // 文章不存在（含已删除）：findRef 空 → ARTICLE_NOT_EXIST（与未发布是两个独立错误码）
        when(articleCatalog.findRef(10L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> commentService.create(dtoOf(10L, "hi", null), jwtOf(2L)));
        assertEquals(StatusCode.ARTICLE_NOT_EXIST.getCode(), ex.getCode());
        verify(commentService, never()).save(any());
    }

    @Test
    void create_onInvisibleArticle_rejectedAsNotPublished() {
        // 存在但不可见（草稿 / 下架 / 定时中 / publish_time 为空）→ ARTICLE_NOT_PUBLISHED
        when(articleCatalog.findRef(10L)).thenReturn(Optional.of(new ArticleRef(10L, 1L, "标题")));
        when(articleCatalog.isVisible(10L)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> commentService.create(dtoOf(10L, "hi", null), jwtOf(2L)));
        assertEquals(StatusCode.ARTICLE_NOT_PUBLISHED.getCode(), ex.getCode());
        verify(commentService, never()).save(any());
    }

    @Test
    void create_reviewRequired_savedPendingWithoutNotification() {
        // P0 §2.1：审核开关开启 → PENDING 落库、不触发通知
        stubVisibleArticle(10L, 1L);
        when(sensitiveWordChecker.containsSensitiveWord(any())).thenReturn(false);
        when(siteConfigService.getBool("comment.review_required", false)).thenReturn(true);
        doReturn(true).when(commentService).save(any(Comment.class));

        CommentCreateResult result = commentService.create(dtoOf(10L, "great article", null), jwtOf(2L));

        assertEquals(CommentStatus.PENDING, result.status());
        verify(sensitiveWordChecker, times(1)).containsSensitiveWord(any());
        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentService).save(captor.capture());
        assertEquals(CommentStatus.PENDING.name(), captor.getValue().getStatus());
        verify(notificationService, never()).notify(any());
    }

    @Test
    void create_reviewRequiredButSensitiveHit_savedFolded() {
        // 分支顺序：敏感词优先于审核开关（命中 → FOLDED，不进入 PENDING）
        stubVisibleArticle(10L, 1L);
        when(sensitiveWordChecker.containsSensitiveWord("bad word here")).thenReturn(true);
        doReturn(true).when(commentService).save(any(Comment.class));

        CommentCreateResult result = commentService.create(dtoOf(10L, "bad word here", null), jwtOf(2L));

        assertEquals(CommentStatus.FOLDED, result.status());
        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentService).save(captor.capture());
        assertEquals(CommentStatus.FOLDED.name(), captor.getValue().getStatus());
        verify(notificationService, never()).notify(any());
    }

    @Test
    void create_siteConfigServiceAbsent_defaultsToNormal() {
        // 容器无 SiteConfigService 实现（合并前/IT 形态）→ 默认关闭审核，评论直接 NORMAL
        when(siteConfigServiceProvider.getIfAvailable()).thenReturn(null);
        stubVisibleArticle(10L, 1L);
        when(sensitiveWordChecker.containsSensitiveWord(any())).thenReturn(false);
        doReturn(true).when(commentService).save(any(Comment.class));

        CommentCreateResult result = commentService.create(dtoOf(10L, "great article", null), jwtOf(2L));

        assertEquals(CommentStatus.NORMAL, result.status());

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentService).save(captor.capture());
        assertEquals(CommentStatus.NORMAL.name(), captor.getValue().getStatus());
        verify(notificationService).notify(NotificationIntent.commentReply(1L, 2L, 10L, null, "great article"));
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
