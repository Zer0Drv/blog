package com.zer0drv.blog.admin;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zer0drv.blog.admin.domain.SensitiveWord;
import com.zer0drv.blog.admin.service.impl.SensitiveWordServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

/**
 * 敏感词命中检测纯单测（MP 继承方法用 spy + doReturn 桩掉，不起容器）。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class SensitiveWordServiceImplTest {

    private SensitiveWordServiceImpl sensitiveWordService;

    @BeforeAll
    static void initTableInfo() {
        // 纯单测无 SqlSessionFactory：手动初始化实体 TableInfo，
        // 否则 LambdaQueryWrapper.select(实体::字段) 找不到 lambda 列缓存
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SensitiveWord.class);
    }

    @BeforeEach
    void setUp() {
        sensitiveWordService = spy(new SensitiveWordServiceImpl());
    }

    private static SensitiveWord word(String value) {
        SensitiveWord word = new SensitiveWord();
        word.setWord(value);
        return word;
    }

    @Test
    void containsSensitiveWord_hitIgnoringCase() {
        doReturn(List.of(word("Bad"), word("spam"))).when(sensitiveWordService).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));

        assertTrue(sensitiveWordService.containsSensitiveWord("this is a BAD word"));
        assertTrue(sensitiveWordService.containsSensitiveWord("SPAM here"));
    }

    @Test
    void containsSensitiveWord_miss_returnsFalse() {
        doReturn(List.of(word("bad"))).when(sensitiveWordService).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));

        assertFalse(sensitiveWordService.containsSensitiveWord("a perfectly fine comment"));
    }

    @Test
    void containsSensitiveWord_blankContent_returnsFalseWithoutQuery() {
        assertFalse(sensitiveWordService.containsSensitiveWord(null));
        assertFalse(sensitiveWordService.containsSensitiveWord(""));
        assertFalse(sensitiveWordService.containsSensitiveWord("   "));
        verify(sensitiveWordService, never()).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    void containsSensitiveWord_emptyWordTable_returnsFalse() {
        doReturn(List.of()).when(sensitiveWordService).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));

        assertFalse(sensitiveWordService.containsSensitiveWord("anything goes"));
    }

    @Test
    void containsSensitiveWord_nullWordInTable_isSkipped() {
        SensitiveWord nullWord = new SensitiveWord();
        doReturn(List.of(nullWord, word("ok-word"))).when(sensitiveWordService).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));

        assertTrue(sensitiveWordService.containsSensitiveWord("say ok-word loudly"));
        assertFalse(sensitiveWordService.containsSensitiveWord("nothing special"));
    }

    @Test
    void add_duplicateWord_isSilentlySkipped() {
        doReturn(1L).when(sensitiveWordService).count(any());

        sensitiveWordService.add("  dup  ");

        verify(sensitiveWordService, never()).save(any());
    }

    @Test
    void add_newWord_isTrimmedAndSaved() {
        doReturn(0L).when(sensitiveWordService).count(any());
        doReturn(true).when(sensitiveWordService).save(any(SensitiveWord.class));

        sensitiveWordService.add("  fresh  ");

        org.mockito.ArgumentCaptor<SensitiveWord> captor = org.mockito.ArgumentCaptor.forClass(SensitiveWord.class);
        verify(sensitiveWordService).save(captor.capture());
        assertTrue("fresh".equals(captor.getValue().getWord()));
    }

    @Test
    void containsSensitiveWord_secondCall_hitsCacheWithoutQuery() {
        // 词表缓存：第二次判定不查库（词表小，全量缓存小写预处理结果）
        doReturn(List.of(word("bad"))).when(sensitiveWordService).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));

        assertTrue(sensitiveWordService.containsSensitiveWord("a BAD word"));
        assertFalse(sensitiveWordService.containsSensitiveWord("totally fine"));

        verify(sensitiveWordService, times(1)).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    void add_newWord_invalidatesCache_takesEffectImmediately() {
        // add 后缓存即时失效：新词不等 TTL 立即生效
        doReturn(List.of(word("bad"))).when(sensitiveWordService).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        assertFalse(sensitiveWordService.containsSensitiveWord("fresh content"));

        doReturn(0L).when(sensitiveWordService).count(any());
        doReturn(true).when(sensitiveWordService).save(any(SensitiveWord.class));
        sensitiveWordService.add("fresh");
        // 模拟库表已写入新词：失效后下次判定重新加载
        doReturn(List.of(word("bad"), word("fresh"))).when(sensitiveWordService).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));

        assertTrue(sensitiveWordService.containsSensitiveWord("fresh content"));
        // 三次判定对应两次查库：首次加载 + add 失效后重载
        verify(sensitiveWordService, times(2)).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    void delete_word_invalidatesCache_takesEffectImmediately() {
        // delete 后缓存即时失效：被删词立即不再命中
        doReturn(List.of(word("bad"))).when(sensitiveWordService).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        assertTrue(sensitiveWordService.containsSensitiveWord("a BAD word"));

        doReturn(true).when(sensitiveWordService).removeById(1L);
        sensitiveWordService.delete(1L);
        doReturn(List.of()).when(sensitiveWordService).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));

        assertFalse(sensitiveWordService.containsSensitiveWord("a BAD word"));
        verify(sensitiveWordService, times(2)).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }
}
