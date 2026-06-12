package com.leyon.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域全局配置类
 * 统一配置接口跨域规则，允许前端指定域名跨域请求、携带Cookie/凭证
 *
 * @author leyon
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 全局路径匹配
     */
    private static final String ALL_PATH_PATTERN = "/**";
    /**
     * 预检请求缓存时长(秒)，1小时
     */
    private static final long MAX_AGE_SECONDS = 3600;
    /**
     * 允许跨域的请求方法
     */
    private static final String[] ALLOWED_METHODS = {"GET", "POST", "PUT", "DELETE", "OPTIONS"};
    /**
     * 前端访问域名列表
     */
    private static final String[] ALLOWED_ORIGINS = {
            "http://localhost:5173",
            "http://localhost:3000",
            "http://127.0.0.1:5173",
            "http://127.0.0.1:3000"
    };

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping(ALL_PATH_PATTERN)
                // 允许指定前端域名跨域
                .allowedOrigins(ALLOWED_ORIGINS)
                // 允许的请求方式
                .allowedMethods(ALLOWED_METHODS)
                // 允许所有请求头
                .allowedHeaders("*")
                // 允许携带Cookie、认证凭证
                .allowCredentials(true)
                // 预检请求缓存时间
                .maxAge(MAX_AGE_SECONDS);
    }
}