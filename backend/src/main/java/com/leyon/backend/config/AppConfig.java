package com.leyon.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.lang.NonNull;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.leyon.backend.interceptor.AdminAuthInterceptor;
import com.leyon.backend.interceptor.AuthInterceptor;
import com.leyon.backend.interceptor.OpenApiAuthInterceptor;
import com.leyon.backend.interceptor.RateLimitInterceptor;

import java.io.IOException;
import java.net.HttpURLConnection;

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
     * 2. RateLimitInterceptor：认证桶（登录/注册/刷新/改密）与高成本桶（OpenAPI、检索试验、录音传输）分档限流
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

        // 速率限制拦截器（v2.35 扩面）：认证桶 4 端点 5 次/分钟 + 高成本桶 4 模式 30 次/分钟，
        // 档位与容量在 RateLimitInterceptor.Tier 内定义；此处注册的路径模式必须覆盖 Tier 的全部端点，
        // 否则漏注册的路径根本不会进入拦截器（Tier 判定只是第二道）。
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns(
                        "/api/auth/login", "/api/auth/register", "/api/auth/refresh", "/api/auth/password",
                        "/api/open/chat", "/api/open/call", "/api/ragflow/retrieval-test",
                        "/api/call-records/*/recording");

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
     * 注入RestTemplate Bean（默认实例，@Primary）
     * 用于服务内部、调用第三方HTTP接口（配置连接/读取超时）
     *
     * @return RestTemplate 实例（已设置超时参数）
     */
    @Bean
    @Primary
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 连接超时：10秒
        factory.setConnectTimeout(10000);
        // 读取超时：60秒（适配大文件上传、文档解析等耗时操作）
        factory.setReadTimeout(60000);
        return new RestTemplate(factory);
    }

    /**
     * Webhook 投递专用 RestTemplate：连接 10s / 读取 15s，且禁用自动重定向
     * 禁用重定向是 SSRF 防线的一环：第三方端点若返回跳转到内网地址的 3xx，
     * 跟随跳转将绕过 {@code ExternalUrlValidator} 的投递前地址校验，故 3xx 直接按投递失败处理
     *
     * @return RestTemplate 实例
     */
    @Bean("webhookRestTemplate")
    public RestTemplate webhookRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                    throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(15000);
        return new RestTemplate(factory);
    }
}