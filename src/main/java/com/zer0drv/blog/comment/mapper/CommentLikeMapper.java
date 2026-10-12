package com.zer0drv.blog.comment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zer0drv.blog.comment.domain.CommentLike;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author Yoruhaki
 */
@Mapper
public interface CommentLikeMapper extends BaseMapper<CommentLike> {
}