package com.leyon.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.leyon.backend.interceptor.AuthInterceptor;

/**
 * 应用全局Web配置类
 * 包含：拦截器注册、RestTemplate HTTP请求工具注入等配置
 * 
 * @author leyon
 */
@Configuration
public class AppConfig implements WebMvcConfigurer {

    /**
     * 认证拦截器（JWT/登录鉴权）
     */
    private final AuthInterceptor authInterceptor;

    /**
     * 构造器注入认证拦截器
     * 
     * @param authInterceptor 自定义认证拦截器
     */
    public AppConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    /**
     * 注册自定义拦截器
     * 拦截规则：拦截所有 /api 开头接口，放行登录、注册等认证相关接口
     * 
     * @param registry 拦截器注册器
     */
    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                // 拦截所有api接口
                .addPathPatterns("/api/**")
                // 排除认证模块接口（登录/注册/刷新令牌等无需鉴权）
                .excludePathPatterns("/api/auth/**");
    }

    /**
     * 注入RestTemplate Bean
     * 用于服务内部、调用第三方HTTP接口
     * 
     * @return RestTemplate 实例
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}