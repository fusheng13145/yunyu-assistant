package com.leyon.backend.interceptor;

import com.leyon.backend.service.RateLimitService;
import com.leyon.backend.util.ClientIpResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 接口速率限制拦截器（v2.35 扩面为两档；v2.45 起计数落在 {@link RateLimitService}）
 * 认证桶：login/register/refresh/password 共用 5 次/分钟/来源（防凭证爆破）
 * 高成本桶：OpenAPI 对话/外呼、RAGFlow 检索试验、录音上传/下载共用 30 次/分钟/来源
 * 桶键的"IP"自 v2.44 起默认取连接层地址；只有确认在反向代理后部署才开 app.security.trust-proxy
 * <p>
 * 本类只负责 HTTP 侧的三件事：放行 OPTIONS 预检、按 URI 归档、把超限写成 429 + Retry-After。
 * 握手类入口（{@code /api/open/ws-voice/*}）不经过 Spring MVC 的 HandlerInterceptor，
 * 由 {@code OpenApiWebSocketAuthInterceptor} 直接调同一个 {@link RateLimitService}——
 * 所以**在本类里加路径模式不会让握手被限流**，档位的完整清单见 {@link RateLimitService.Tier}。
 *
 * @author leyon
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    /** 计数与档位的唯一实现（与握手链路共用） */
    private final RateLimitService rateLimitService;

    /**
     * 客户端地址判定（v2.44）：注入而不是自己读配置，避免限流桶键与登录锁定的来源维度
     * 各自解析一次代理信任、进而在两处口径漂移
     */
    private final ClientIpResolver clientIpResolver;

    public RateLimitInterceptor(RateLimitService rateLimitService, ClientIpResolver clientIpResolver) {
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    /** 跨域预检请求方法（放行，不计数） */
    private static final String OPTIONS_METHOD = "OPTIONS";

    /** 429 Too Many Requests */
    private static final int TOO_MANY_REQUESTS_CODE = 429;

    /** 响应内容类型 */
    private static final String JSON_CONTENT_TYPE = "application/json;charset=UTF-8";

    /** Retry-After 响应头名称 */
    private static final String RETRY_AFTER_HEADER = "Retry-After";

    /** 429 统一返回报文 */
    private static final String RATE_LIMIT_RESPONSE_TEMPLATE =
            "{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null}";

    /**
     * 前置拦截处理：在请求进入控制器前进行速率检查
     *
     * @param request  请求对象
     * @param response 响应对象
     * @param handler  处理器
     * @return true-放行请求，false-拦截请求并返回 429
     */
    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws IOException {
        // 放行跨域 OPTIONS 预检请求
        if (OPTIONS_METHOD.equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 仅对分档内的路径进行速率限制（v2.35：认证桶 + 高成本桶）
        RateLimitService.Tier tier = RateLimitService.Tier.resolveMvc(request.getRequestURI());
        if (tier == null) {
            return true;
        }

        // 桶键＝服务端能证明的来源（v2.44：默认只信连接层地址，代理头须显式开信任）
        RateLimitService.Decision decision =
                rateLimitService.tryAcquire(tier, clientIpResolver.resolve(request));
        if (!decision.allowed()) {
            writeRateLimitResponse(response, decision.retryAfterSeconds());
            return false;
        }
        return true;
    }

    /** 写入 429 响应（Retry-After 由计数侧给出的剩余秒数） */
    private void writeRateLimitResponse(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(TOO_MANY_REQUESTS_CODE);
        response.setContentType(JSON_CONTENT_TYPE);
        response.setHeader(RETRY_AFTER_HEADER, String.valueOf(retryAfterSeconds));
        response.getWriter().write(RATE_LIMIT_RESPONSE_TEMPLATE);
    }
}
