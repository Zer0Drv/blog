package com.zer0drv.blog.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.zer0drv.blog.user.domain.User;

/**
 * @author Yoruhaki
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
