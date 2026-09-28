package com.zer0drv.blog.tag.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zer0drv.blog.article.domain.ArticleTag;
import com.zer0drv.blog.article.mapper.ArticleTagMapper;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.tag.domain.Tag;
import com.zer0drv.blog.tag.dto.TagSaveDTO;
import com.zer0drv.blog.tag.mapper.TagMapper;
import com.zer0drv.blog.tag.service.TagService;
import com.zer0drv.blog.tag.vo.TagVO;
import io.github.linpeilie.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class TagServiceImpl extends ServiceImpl<TagMapper, Tag> implements TagService {

    private final ArticleTagMapper articleTagMapper;
    private final Converter converter;

    @Override
    public List<TagVO> listAll() {
        return list(Wrappers.lambdaQuery(Tag.class).orderByAsc(Tag::getId)).stream()
                .map(tag -> converter.convert(tag, TagVO.class))
                .toList();
    }

    @Override
    public Long create(TagSaveDTO dto) {
        String name = dto.getName().trim();
        assertNameUnique(name, null);
        Tag tag = new Tag();
        tag.setName(name);
        save(tag);
        return tag.getId();
    }

    @Override
    public void update(Long id, TagSaveDTO dto) {
        Tag tag = requireTag(id);
        String name = dto.getName().trim();
        assertNameUnique(name, id);
        tag.setName(name);
        updateById(tag);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireTag(id);
        removeById(id);
        // 清理文章-标签关联（关联表无逻辑删除字段，物理删除）
        articleTagMapper.delete(Wrappers.lambdaQuery(ArticleTag.class).eq(ArticleTag::getTagId, id));
    }

    private Tag requireTag(Long id) {
        Tag tag = getById(id);
        if (Objects.isNull(tag)) {
            throw new BusinessException(StatusCode.TAG_NOT_EXIST);
        }
        return tag;
    }

    /**
     * 标签名唯一（uk_name）；excludeId 用于编辑时排除自身
     */
    private void assertNameUnique(String name, Long excludeId) {
        long count = count(Wrappers.lambdaQuery(Tag.class)
                .eq(Tag::getName, name)
                .ne(Objects.nonNull(excludeId), Tag::getId, excludeId));
        if (count > 0) {
            throw new BusinessException(StatusCode.TAG_EXISTS);
        }
    }
}