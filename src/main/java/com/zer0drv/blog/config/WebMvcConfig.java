package com.zer0drv.blog.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * Web MVC 配置：把 /uploads/** 映射到本地上传目录。
 *
 * @author Yoruhaki
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 上传根目录，与 UploadServiceImpl 保持一致（BLOG_UPLOAD_DIR 可覆盖）
     */
    @Value("${blog.upload-dir:./uploads}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // toUri() 自带 file: 前缀与结尾分隔符，目录不存在时请求只会 404，不影响启动
        String location = Paths.get(uploadDir).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }
}