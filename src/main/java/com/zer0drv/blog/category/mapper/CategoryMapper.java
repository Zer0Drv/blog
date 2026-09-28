package com.zer0drv.blog.category.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zer0drv.blog.category.domain.Category;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author Yoruhaki
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}