package com.leyon.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.lang.NonNull;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.leyon.backend.interceptor.AdminAuthInterceptor;
import com.leyon.backend.interceptor.AuthInterceptor;
import com.leyon.backend.interceptor.OpenApiAuthInterceptor;
import com.leyon.backend.interceptor.RateLimitInterceptor;

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
     * 速率限制拦截器（防暴力破解）
     */
    private final RateLimitInterceptor rateLimitInterceptor;
    /**
     * 管理员接口鉴权拦截器
     */
    private final AdminAuthInterceptor adminAuthInterceptor;
    /**
     * 开放 OpenAPI 鉴权拦截器（X-API-Key，与 JWT 通道隔离）
     */
    private final OpenApiAuthInterceptor openApiAuthInterceptor;

    /**
     * 构造器注入拦截器
     *
     * @param authInterceptor       自定义认证拦截器
     * @param rateLimitInterceptor  速率限制拦截器
     * @param adminAuthInterceptor  管理员鉴权拦截器
     * @param openApiAuthInterceptor OpenAPI 鉴权拦截器
     */
    public AppConfig(AuthInterceptor authInterceptor, RateLimitInterceptor rateLimitInterceptor,
                     AdminAuthInterceptor adminAuthInterceptor, OpenApiAuthInterceptor openApiAuthInterceptor) {
        this.authInterceptor = authInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.adminAuthInterceptor = adminAuthInterceptor;
        this.openApiAuthInterceptor = openApiAuthInterceptor;
    }

    /**
     * 注册自定义拦截器
     * 拦截规则：
     * 1. AuthInterceptor：拦截所有 /api 开头接口，放行登录/注册等认证接口
     * 2. RateLimitInterceptor：仅对登录/注册接口进行速率限制（防暴力破解）
     *
     * @param registry 拦截器注册器
     */
    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        // JWT 认证拦截器
        registry.addInterceptor(authInterceptor)
                // 拦截所有api接口
                .addPathPatterns("/api/**")
                // 排除认证模块接口（登录/注册/刷新令牌等无需鉴权）
                .excludePathPatterns("/api/auth/**")
                // 排除开放 OpenAPI（由 OpenApiAuthInterceptor 以 API Key 鉴权，与 JWT 通道隔离）
                .excludePathPatterns("/api/open/**");

        // 速率限制拦截器（仅限制登录和注册接口）
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/auth/login", "/api/auth/register");

        // 管理员接口鉴权拦截器（依赖 AuthInterceptor 解析的 userId，须在其之后）
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/admin/**");

        // 开放 OpenAPI 鉴权拦截器（X-API-Key 校验 /api/open/**，独立于 JWT 通道）
        // 排除 PSTN 网关回调端点（/api/open/callbacks/** 用独立 X-Gateway-Token 校验）
        registry.addInterceptor(openApiAuthInterceptor)
                .addPathPatterns("/api/open/**")
                .excludePathPatterns("/api/open/callbacks/**");
    }

    /**
     * 注入RestTemplate Bean
     * 用于服务内部、调用第三方HTTP接口（配置连接/读取超时）
     *
     * @return RestTemplate 实例（已设置超时参数）
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 连接超时：10秒
        factory.setConnectTimeout(10000);
        // 读取超时：60秒（适配大文件上传、文档解析等耗时操作）
        factory.setReadTimeout(60000);
        return new RestTemplate(factory);
    }
}