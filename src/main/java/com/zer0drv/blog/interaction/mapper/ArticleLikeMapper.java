package com.zer0drv.blog.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zer0drv.blog.interaction.domain.ArticleLike;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author Yoruhaki
 */
@Mapper
public interface ArticleLikeMapper extends BaseMapper<ArticleLike> {
}