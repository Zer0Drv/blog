package com.zer0drv.blog.custompage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zer0drv.blog.custompage.domain.CustomPage;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CustomPageMapper extends BaseMapper<CustomPage> {
    // 包括已删除页面，保留已发布过的 URL 占位。
    @Select("SELECT id FROM custom_page WHERE slug = #{slug}")
    Long findSlugOwner(@Param("slug") String slug);
}
