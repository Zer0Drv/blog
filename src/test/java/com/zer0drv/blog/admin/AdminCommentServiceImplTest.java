package com.zer0drv.blog.admin;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zer0drv.blog.admin.service.impl.AdminCommentServiceImpl;
import com.zer0drv.blog.article.api.ArticleCatalog;
import com.zer0drv.blog.article.api.ArticleRef;
import com.zer0drv.blog.comment.domain.Comment;
import com.zer0drv.blog.comment.enums.CommentStatus;
import com.zer0drv.blog.comment.mapper.CommentMapper;
import com.zer0drv.blog.comment.service.CommentService;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.interaction.domain.CommentLike;
import com.zer0drv.blog.interaction.mapper.CommentLikeMapper;
import com.zer0drv.blog.user.service.UserService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminCommentServiceImpl 纯单测（P0 §2.1/§2.4）：
 * approve/reject 状态机（仅 PENDING 可执行）、approve 补发通知、回收站 restore/forceDelete 级联。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class AdminCommentServiceImplTest {

    @Mock
    private CommentMapper commentMapper;
    @Mock
    private ArticleCatalog articleCatalog;
    @Mock
    private UserService userService;
    @Mock
    private CommentService commentService;
    @Mock
    private CommentLikeMapper commentLikeMapper;

    private AdminCommentServiceImpl adminCommentService;

    @BeforeAll
    static void initTableInfo() {
        // 纯单测无 SqlSessionFactory：手动初始化实体 TableInfo，
        // 否则 LambdaQueryWrapper/LambdaUpdateWrapper 解析列名时抛 "can not find lambda cache"
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Comment.class);
        TableInfoHelper.initTableInfo(assistant, CommentLike.class);
    }

    @BeforeEach
    void setUp() {
        adminCommentService = new AdminCommentServiceImpl(
                commentMapper, articleCatalog, userService, commentService, commentLikeMapper);
    }

    private static Comment comment(long id, long parentId, String status) {
        Comment comment = new Comment();
        comment.setId(id);
        comment.setArticleId(10L);
        comment.setUserId(2L);
        comment.setParentId(parentId);
        comment.setContent("content" + id);
        comment.setStatus(status);
        return comment;
    }

    @Test
    void approve_pending_setsNormalAndResendsNotification() {
        Comment pending = comment(20L, 0L, CommentStatus.PENDING.name());
        when(commentMapper.selectById(20L)).thenReturn(pending);
        ArticleRef article = new ArticleRef(10L, 1L, "标题");
        when(articleCatalog.findRef(10L)).thenReturn(Optional.of(article));

        adminCommentService.approve(20L);

        verify(commentMapper).update(eq(null), any());
        // 审核通过补发通知（走 create 同一 notify 路径）
        verify(commentService).notifyCommentCreated(pending, article);
    }

    @Test
    void approve_nonPending_rejected() {
        Comment normal = comment(20L, 0L, CommentStatus.NORMAL.name());
        when(commentMapper.selectById(20L)).thenReturn(normal);

        BusinessException ex = assertThrows(BusinessException.class, () -> adminCommentService.approve(20L));
        assertEquals(StatusCode.PARAM_INVALID.getCode(), ex.getCode());
        assertEquals("仅待审核评论可执行该操作", ex.getMessage());
        verify(commentMapper, never()).update(any(), any());
        verify(commentService, never()).notifyCommentCreated(any(), any());
    }

    @Test
    void approve_articleMissing_skipsNotification() {
        // 文章已被删（查不到）：状态照常流转，仅跳过补发通知
        Comment pending = comment(20L, 0L, CommentStatus.PENDING.name());
        when(commentMapper.selectById(20L)).thenReturn(pending);
        when(articleCatalog.findRef(10L)).thenReturn(Optional.empty());

        adminCommentService.approve(20L);

        verify(commentMapper).update(eq(null), any());
        verify(commentService, never()).notifyCommentCreated(any(), any());
    }

    @Test
    void reject_pending_setsFoldedWithoutNotification() {
        Comment pending = comment(20L, 0L, CommentStatus.PENDING.name());
        when(commentMapper.selectById(20L)).thenReturn(pending);

        adminCommentService.reject(20L);

        verify(commentMapper).update(eq(null), any());
        verify(commentService, never()).notifyCommentCreated(any(), any());
    }

    @Test
    void reject_nonPending_rejected() {
        Comment folded = comment(20L, 0L, CommentStatus.FOLDED.name());
        when(commentMapper.selectById(20L)).thenReturn(folded);

        BusinessException ex = assertThrows(BusinessException.class, () -> adminCommentService.reject(20L));
        assertEquals(StatusCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(commentMapper, never()).update(any(), any());
    }

    @Test
    void restore_deletedRoot_cascadesReplies() {
        Comment deletedRoot = comment(20L, 0L, CommentStatus.NORMAL.name());
        deletedRoot.setDeleted((short) 1);
        when(commentMapper.selectDeletedById(20L)).thenReturn(deletedRoot);

        adminCommentService.restore(20L);

        verify(commentMapper).restoreById(20L);
        // 主评论连带恢复其 deleted=1 的回复（近似）
        verify(commentMapper).restoreRepliesByRootId(20L);
    }

    @Test
    void restore_deletedReply_doesNotCascade() {
        Comment deletedReply = comment(21L, 20L, CommentStatus.NORMAL.name());
        deletedReply.setDeleted((short) 1);
        when(commentMapper.selectDeletedById(21L)).thenReturn(deletedReply);

        adminCommentService.restore(21L);

        verify(commentMapper).restoreById(21L);
        verify(commentMapper, never()).restoreRepliesByRootId(any());
    }

    @Test
    void restore_notInTrash_rejected() {
        when(commentMapper.selectDeletedById(404L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> adminCommentService.restore(404L));
        assertEquals(StatusCode.COMMENT_NOT_EXIST.getCode(), ex.getCode());
        verify(commentMapper, never()).restoreById(any());
    }

    @Test
    void forceDelete_root_cascadesRepliesAndLikes() {
        Comment root = comment(20L, 0L, CommentStatus.NORMAL.name());
        when(commentMapper.selectAnyById(20L)).thenReturn(root);
        when(commentMapper.selectReplyIdsByRootId(20L)).thenReturn(List.of(21L, 22L));

        adminCommentService.forceDelete(20L);

        // 主评论：连带物理删全部回复与主评论+回复的点赞关联
        verify(commentMapper).physicalDeleteRepliesByRootId(20L);
        verify(commentMapper).physicalDeleteById(20L);
        verify(commentLikeMapper).delete(any());
    }

    @Test
    void forceDelete_reply_doesNotCascadeReplies() {
        Comment reply = comment(21L, 20L, CommentStatus.NORMAL.name());
        when(commentMapper.selectAnyById(21L)).thenReturn(reply);

        adminCommentService.forceDelete(21L);

        verify(commentMapper, never()).physicalDeleteRepliesByRootId(any());
        verify(commentMapper).physicalDeleteById(21L);
        verify(commentLikeMapper).delete(any());
    }

    @Test
    void forceDelete_missing_rejected() {
        when(commentMapper.selectAnyById(404L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> adminCommentService.forceDelete(404L));
        assertEquals(StatusCode.COMMENT_NOT_EXIST.getCode(), ex.getCode());
        verify(commentMapper, never()).physicalDeleteById(any());
    }

    @Test
    void pageComments_trash_usesCustomSql() {
        // 伪状态 TRASH：绕过 MP 逻辑删除走自定义 SQL
        when(commentMapper.pageTrash(any(), eq("kw"), eq(10L)))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10));

        adminCommentService.pageComments(1, 10, "TRASH", "kw", 10L);

        verify(commentMapper).pageTrash(any(), eq("kw"), eq(10L));
        verify(commentMapper, never()).selectPage(any(), any());
    }
}
