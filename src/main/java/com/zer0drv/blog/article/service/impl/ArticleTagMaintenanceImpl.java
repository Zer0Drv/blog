package com.zer0drv.blog.article.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zer0drv.blog.article.api.ArticleTagMaintenance;
import com.zer0drv.blog.article.domain.ArticleTag;
import com.zer0drv.blog.article.mapper.ArticleTagMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link ArticleTagMaintenance} 实现（article 内部包，issue #34 第四步）：
 * 清理 SQL 与收口前 TagServiceImpl.delete 的内联实现逐字节等价
 * （按 tag_id 物理删除，关联表无逻辑删除字段）。
 *
 * @author Yoruhaki
 */
@Component
@RequiredArgsConstructor
public class ArticleTagMaintenanceImpl implements ArticleTagMaintenance {

    private final ArticleTagMapper articleTagMapper;

    @Override
    public void unlinkTag(long tagId) {
        articleTagMapper.delete(Wrappers.lambdaQuery(ArticleTag.class).eq(ArticleTag::getTagId, tagId));
    }
}
