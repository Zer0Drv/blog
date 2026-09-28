package com.zer0drv.blog.comment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zer0drv.blog.comment.domain.Comment;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author Yoruhaki
 */
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {
}