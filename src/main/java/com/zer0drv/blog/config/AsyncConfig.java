package com.zer0drv.blog.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 异步支持（P0 §2.2：评论邮件通知 @Async 异步发送）
 *
 * @author Yoruhaki
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
