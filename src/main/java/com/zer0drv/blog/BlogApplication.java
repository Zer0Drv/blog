package com.zer0drv.blog;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @author Yoruhaki
 */
@SpringBootApplication
// 只扫 mappers 会漏掉后续模块：@MapperScan 一旦出现，MyBatis 的 @Mapper 自动扫描
// （AutoConfiguredMapperScannerRegistrar，条件是 @ConditionalOnMissingBean）就会被关掉，
// 未纳入扫描的 Mapper 会直接报 “No qualifying bean of type ...Mapper”。
// 因此这里扫全包 + 限定只注册带 @Mapper 的接口（避免把 Service 接口误注册成 Mapper）。
@MapperScan(basePackages = "com.zer0drv.blog", annotationClass = Mapper.class)
public class BlogApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlogApplication.class, args);
    }
}
