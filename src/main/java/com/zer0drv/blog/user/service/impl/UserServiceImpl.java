package com.zer0drv.blog.user.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.mapper.UserMapper;
import com.zer0drv.blog.user.service.UserService;
import org.springframework.stereotype.Service;

/**
 * @author Yoruhaki
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
}
