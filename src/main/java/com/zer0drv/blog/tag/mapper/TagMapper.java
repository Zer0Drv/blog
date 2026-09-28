package com.zer0drv.blog.tag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zer0drv.blog.tag.domain.Tag;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author Yoruhaki
 */
@Mapper
public interface TagMapper extends BaseMapper<Tag> {
}