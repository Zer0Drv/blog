package com.zer0drv.blog.comment;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zer0drv.blog.comment.domain.Comment;
import com.zer0drv.blog.comment.mapper.CommentMapper;
import com.zer0drv.blog.comment.service.impl.CommentArticleStatsPortImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * article.api.ArticleCommentStatsPort 实现纯单测（issue #34 第三步）：
 * 评论计数口径钉住「仅 status=NORMAL」，与收口前 ArticleServiceImpl.fillInteraction 一致。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class CommentArticleStatsPortImplTest {

    @Mock
    private CommentMapper commentMapper;

    private CommentArticleStatsPortImpl commentArticleStatsPort;

    @BeforeAll
    static void initTableInfo() {
        // 纯单测无 SqlSessionFactory：手动初始化实体 TableInfo，
        // 否则 LambdaQueryWrapper 解析列名时抛 "can not find lambda cache"
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Comment.class);
    }

    @BeforeEach
    void setUp() {
        commentArticleStatsPort = new CommentArticleStatsPortImpl(commentMapper);
    }

    @Test
    void countVisibleByArticleId_countsNormalStatusOnly() {
        when(commentMapper.selectCount(any())).thenReturn(5L);

        long count = commentArticleStatsPort.countVisibleByArticleId(10L);

        assertEquals(5L, count);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Comment>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(commentMapper).selectCount(captor.capture());
        // 口径：article_id 等值 + status=NORMAL（FOLDED / PENDING 不计）
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("article_id") && sql.contains("status"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("NORMAL"));
    }
}
