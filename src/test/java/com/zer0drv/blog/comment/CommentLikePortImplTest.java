package com.zer0drv.blog.comment;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zer0drv.blog.comment.api.CommentRef;
import com.zer0drv.blog.comment.domain.Comment;
import com.zer0drv.blog.comment.domain.CommentLike;
import com.zer0drv.blog.comment.enums.CommentStatus;
import com.zer0drv.blog.comment.mapper.CommentLikeMapper;
import com.zer0drv.blog.comment.mapper.CommentMapper;
import com.zer0drv.blog.comment.service.impl.CommentLikePortImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * comment.api.CommentLikePort 实现纯单测（issue #34 第一步）：
 * 「仅 NORMAL 可点赞」判定、点赞幂等（含并发唯一索引兜底）、
 * like_count 原子 ±1、确实删到关联才扣减——口径与收口前 InteractionServiceImpl 逐字节等价。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class CommentLikePortImplTest {

    @Mock
    private CommentLikeMapper commentLikeMapper;
    @Mock
    private CommentMapper commentMapper;

    private CommentLikePortImpl commentLikePort;

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
        commentLikePort = new CommentLikePortImpl(commentLikeMapper, commentMapper);
    }

    private static Comment comment(long id, String status) {
        Comment comment = new Comment();
        comment.setId(id);
        comment.setUserId(1L);
        comment.setArticleId(10L);
        comment.setContent("nice");
        comment.setStatus(status);
        return comment;
    }

    // ---------- findLikeable ----------

    @Test
    void findLikeable_normalComment_returnsProjection() {
        when(commentMapper.selectById(100L)).thenReturn(comment(100L, CommentStatus.NORMAL.name()));

        Optional<CommentRef> ref = commentLikePort.findLikeable(100L);

        assertTrue(ref.isPresent());
        assertEquals(100L, ref.get().id());
        assertEquals(1L, ref.get().authorId());
        assertEquals(10L, ref.get().articleId());
        assertEquals("nice", ref.get().content());
    }

    @Test
    void findLikeable_missingComment_returnsEmpty() {
        when(commentMapper.selectById(100L)).thenReturn(null);

        assertTrue(commentLikePort.findLikeable(100L).isEmpty());
    }

    @Test
    void findLikeable_foldedComment_returnsEmpty() {
        when(commentMapper.selectById(100L)).thenReturn(comment(100L, CommentStatus.FOLDED.name()));

        assertTrue(commentLikePort.findLikeable(100L).isEmpty());
    }

    @Test
    void findLikeable_pendingComment_returnsEmpty() {
        when(commentMapper.selectById(100L)).thenReturn(comment(100L, CommentStatus.PENDING.name()));

        assertTrue(commentLikePort.findLikeable(100L).isEmpty());
    }

    // ---------- addLike ----------

    @Test
    void addLike_firstTime_insertsAndIncrementsCount() {
        when(commentLikeMapper.selectCount(any())).thenReturn(0L);

        boolean added = commentLikePort.addLike(100L, 2L);

        assertTrue(added);
        verify(commentLikeMapper).insert(any(CommentLike.class));
        // like_count 原子自增：setSql 一条 update，不先查后写
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Comment>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(commentMapper).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("like_count = like_count + 1"));
    }

    @Test
    void addLike_alreadyLiked_isSilentWithoutCountChange() {
        when(commentLikeMapper.selectCount(any())).thenReturn(1L);

        boolean added = commentLikePort.addLike(100L, 2L);

        assertFalse(added);
        verify(commentLikeMapper, never()).insert(any(CommentLike.class));
        verify(commentMapper, never()).update(any(), any());
    }

    @Test
    void addLike_concurrentDuplicate_isSilentWithoutCountChange() {
        when(commentLikeMapper.selectCount(any())).thenReturn(0L);
        doThrow(new DuplicateKeyException("uk")).when(commentLikeMapper).insert(any(CommentLike.class));

        // 唯一索引兜底：不抛异常、不累加计数
        boolean added = commentLikePort.addLike(100L, 2L);

        assertFalse(added);
        verify(commentMapper, never()).update(any(), any());
    }

    // ---------- removeLike ----------

    @Test
    void removeLike_existingLike_decrementsCount() {
        when(commentLikeMapper.delete(any())).thenReturn(1);

        commentLikePort.removeLike(100L, 2L);

        // 确实删到一条才扣减计数：原子 -1 且带 like_count > 0 兜底
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Comment>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(commentMapper).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("like_count = like_count - 1"));
        assertTrue(captor.getValue().getSqlSegment().contains("like_count"));
    }

    @Test
    void removeLike_noLike_doesNotTouchCount() {
        when(commentLikeMapper.delete(any())).thenReturn(0);

        commentLikePort.removeLike(100L, 2L);

        verify(commentMapper, never()).update(any(), any());
    }

    @Test
    void removeLike_deletesByCommentAndUser() {
        when(commentLikeMapper.delete(any())).thenReturn(1);

        commentLikePort.removeLike(100L, 2L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<CommentLike>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(commentLikeMapper).delete(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("comment_id") && sql.contains("user_id"));
    }
}
