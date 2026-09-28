package com.zer0drv.blog.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zer0drv.blog.article.domain.Article;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author Yoruhaki
 */
@Mapper
public interface ArticleMapper extends BaseMapper<Article> {
}