package com.zer0drv.blog.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zer0drv.blog.admin.domain.SensitiveWord;
import com.zer0drv.blog.admin.mapper.SensitiveWordMapper;
import com.zer0drv.blog.admin.service.SensitiveWordService;
import com.zer0drv.blog.admin.vo.SensitiveWordVO;
import com.zer0drv.blog.common.response.PageResult;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Yoruhaki
 */
@Service
public class SensitiveWordServiceImpl extends ServiceImpl<SensitiveWordMapper, SensitiveWord>
        implements SensitiveWordService {

    /**
     * 词表缓存 TTL 兜底：即使存在绕过 add/delete 的改库路径，也最多滞后 5 分钟生效
     */
    private static final long WORD_CACHE_TTL_MILLIS = Duration.ofMinutes(5).toMillis();

    /**
     * 小写预处理后的词表缓存（null = 未加载/已失效）。词表小，全量缓存避免每条评论全表 select
     */
    private final AtomicReference<List<String>> wordCache = new AtomicReference<>();

    /**
     * 缓存过期时间点（epoch millis）；与 wordCache 配对使用，失效时一并归零
     */
    private volatile long wordCacheExpiresAt = 0L;

    @Override
    public PageResult<SensitiveWordVO> page(long page, long size, String keyword) {
        Page<SensitiveWord> result = page(new Page<>(page, size), Wrappers.lambdaQuery(SensitiveWord.class)
                .like(Objects.nonNull(keyword) && !keyword.isBlank(), SensitiveWord::getWord, keyword)
                .orderByDesc(SensitiveWord::getId));
        List<SensitiveWordVO> records = result.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(records, result.getTotal(), page, size);
    }

    @Override
    public void add(String word) {
        String trimmed = word.trim();
        long exists = count(Wrappers.lambdaQuery(SensitiveWord.class).eq(SensitiveWord::getWord, trimmed));
        if (exists > 0) {
            // 去重：重复词静默成功
            return;
        }
        SensitiveWord sensitiveWord = new SensitiveWord();
        sensitiveWord.setWord(trimmed);
        save(sensitiveWord);
        invalidateWordCache();
    }

    @Override
    public void delete(Long id) {
        removeById(id);
        invalidateWordCache();
    }

    @Override
    public boolean containsSensitiveWord(String content) {
        if (Objects.isNull(content) || content.isBlank()) {
            return false;
        }
        List<String> words = cachedWords();
        if (words.isEmpty()) {
            return false;
        }
        String lowerContent = content.toLowerCase(Locale.ROOT);
        return words.stream().anyMatch(lowerContent::contains);
    }

    /**
     * 读缓存词表（小写预处理）；未命中或超过 TTL 时全量加载并回填。
     * 并发下可能重复加载，结果一致，无害。
     */
    private List<String> cachedWords() {
        List<String> cached = wordCache.get();
        if (Objects.nonNull(cached) && System.currentTimeMillis() < wordCacheExpiresAt) {
            return cached;
        }
        List<String> words = list(Wrappers.lambdaQuery(SensitiveWord.class)
                        .select(SensitiveWord::getWord))
                .stream()
                .map(SensitiveWord::getWord)
                .filter(Objects::nonNull)
                .map(word -> word.toLowerCase(Locale.ROOT))
                .toList();
        wordCache.set(words);
        wordCacheExpiresAt = System.currentTimeMillis() + WORD_CACHE_TTL_MILLIS;
        return words;
    }

    private void invalidateWordCache() {
        wordCache.set(null);
        wordCacheExpiresAt = 0L;
    }

    private SensitiveWordVO toVO(SensitiveWord word) {
        SensitiveWordVO vo = new SensitiveWordVO();
        vo.setId(word.getId());
        vo.setWord(word.getWord());
        vo.setCreateTime(word.getCreateTime());
        return vo;
    }
}
