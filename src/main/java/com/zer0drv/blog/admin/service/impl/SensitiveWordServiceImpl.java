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

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * @author Yoruhaki
 */
@Service
public class SensitiveWordServiceImpl extends ServiceImpl<SensitiveWordMapper, SensitiveWord>
        implements SensitiveWordService {

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
    }

    @Override
    public void delete(Long id) {
        removeById(id);
    }

    @Override
    public boolean containsSensitiveWord(String content) {
        if (Objects.isNull(content) || content.isBlank()) {
            return false;
        }
        List<String> words = list(Wrappers.lambdaQuery(SensitiveWord.class)
                        .select(SensitiveWord::getWord))
                .stream().map(SensitiveWord::getWord).toList();
        if (words.isEmpty()) {
            return false;
        }
        String lowerContent = content.toLowerCase(Locale.ROOT);
        return words.stream()
                .filter(Objects::nonNull)
                .map(word -> word.toLowerCase(Locale.ROOT))
                .anyMatch(lowerContent::contains);
    }

    private SensitiveWordVO toVO(SensitiveWord word) {
        SensitiveWordVO vo = new SensitiveWordVO();
        vo.setId(word.getId());
        vo.setWord(word.getWord());
        vo.setCreateTime(word.getCreateTime());
        return vo;
    }
}
