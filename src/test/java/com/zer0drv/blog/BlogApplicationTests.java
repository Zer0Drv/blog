package com.zer0drv.blog;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring 上下文烟雾测试：加载完整应用上下文（H2 内存库替代 MySQL，MinIO 关闭，
 * 无需任何外部服务），是 BUG-01 类 Bean 循环依赖/装配故障的回归保护。
 *
 * @author Yoruhaki
 */
@SpringBootTest
class BlogApplicationTests {

    @Test
    void contextLoads() {
    }
}
