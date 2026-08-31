package com.leyon.backend.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接口速率限制拦截器
 * 基于内存的简易速率限制，防止登录/注册接口被暴力破解
 * 使用滑动窗口算法，同一 IP 每分钟最多允许指定次数的请求
 *
 * @author leyon
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    /**
     * 请求记录：IP → 请求时间戳队列（毫秒）
     * 使用 ConcurrentHashMap 保证线程安全
     */
    private final Map<String, LinkedList<Long>> requestRecords = new ConcurrentHashMap<>();

    /**
     * 上次全量清理时间戳，用于节流清理频率
     */
    private volatile long lastCleanupTime = System.currentTimeMillis();

    /**
     * 全量清理间隔（毫秒）：每60秒最多执行一次全量扫描
     */
    private static final long CLEANUP_INTERVAL_MS = 60_000L;

    /**
     * 过期 IP 条目存活时间（毫秒）：5分钟无请求后视为过期
     */
    private static final long STALE_ENTRY_TTL_MS = 5 * 60_000L;

    /**
     * 时间窗口大小（毫秒）：1分钟 = 60000ms
     */
    private static final long WINDOW_MS = 60_000L;

    /**
     * 单个 IP 在时间窗口内允许的最大请求次数
     */
    private static final int MAX_REQUESTS_PER_WINDOW = 5;

    /**
     * 需要限流的接口路径前缀（认证相关接口）
     */
    private static final String[] RATE_LIMIT_PATHS = {
            "/api/auth/login",
            "/api/auth/register"
    };

    /**
     * 跨域预检请求方法（放行，不计数）
     */
    private static final String OPTIONS_METHOD = "OPTIONS";

    /**
     * 请求过多状态码：429 Too Many Requests
     */
    private static final int TOO_MANY_REQUESTS_CODE = 429;

    /**
     * 响应内容类型
     */
    private static final String JSON_CONTENT_TYPE = "application/json;charset=UTF-8";

    /**
     * Retry-After 响应头名称
     */
    private static final String RETRY_AFTER_HEADER = "Retry-After";

    /**
     * 429 统一返回报文
     */
    private static final String RATE_LIMIT_RESPONSE_TEMPLATE =
            "{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null}";

    /**
     * 前置拦截处理：在请求进入控制器前进行速率检查
     *
     * @param request  请求对象
     * @param response 响应对象
     * @param handler  处理器
     * @return true-放行请求，false-拦截请求并返回429
     */
    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws IOException {
        // 放行跨域 OPTIONS 预检请求
        if (OPTIONS_METHOD.equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 仅对指定路径进行速率限制
        String requestUri = request.getRequestURI();
        if (!shouldRateLimit(requestUri)) {
            return true;
        }

        // 获取客户端真实IP（优先 X-Forwarded-For，其次 RemoteAddr）
        String clientIp = getClientIp(request);

        // 检查是否超出速率限制
        if (isRateLimited(clientIp, response)) {
            return false;
        }

        return true;
    }

    /**
     * 判断当前请求路径是否需要速率限制
     *
     * @param uri 请求 URI
     * @return 是否需要限流
     */
    private boolean shouldRateLimit(String uri) {
        for (String path : RATE_LIMIT_PATHS) {
            if (path.equals(uri)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取客户端真实 IP 地址
     * 优先从代理头获取，兜底使用 RemoteAddr
     *
     * @param request 请求对象
     * @return 客户端 IP 字符串
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // 取第一个IP（最原始的客户端IP）
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 执行速率限制检查（滑动窗口算法）
     * 清除过期记录后判断当前窗口内请求数是否超限
     *
     * @param clientIp 客户端 IP
     * @param response 响应对象（用于设置429响应头）
     * @return true-已限流需拦截，false-未超限可放行
     */
    private boolean isRateLimited(String clientIp, HttpServletResponse response) throws IOException {
        long now = System.currentTimeMillis();

        // 惰性全量清理：每隔 CLEANUP_INTERVAL_MS 扫描一次，移除长时间无请求的过期 IP 条目
        if (now - lastCleanupTime > CLEANUP_INTERVAL_MS) {
            cleanupStaleEntries(now);
            lastCleanupTime = now;
        }

        // 获取或创建该 IP 的请求记录队列
        LinkedList<Long> timestamps = requestRecords.computeIfAbsent(
                clientIp, k -> new LinkedList<>()
        );

        synchronized (timestamps) {
            // 清除窗口外的过期记录
            long windowStart = now - WINDOW_MS;
            Iterator<Long> iterator = timestamps.iterator();
            while (iterator.hasNext()) {
                if (iterator.next() < windowStart) {
                    iterator.remove();
                } else {
                    // LinkedList 按时间顺序排列，遇到第一条未过期的即可停止
                    break;
                }
            }

            // 检查是否超过限制
            if (timestamps.size() >= MAX_REQUESTS_PER_WINDOW) {
                // 计算剩余等待时间（秒）
                long oldestInWindow = timestamps.getFirst();
                long retryAfterSeconds = Math.max(1, (oldestInWindow + WINDOW_MS - now + 999) / 1000);

                // 设置 429 响应头和状态码
                response.setStatus(TOO_MANY_REQUESTS_CODE);
                response.setContentType(JSON_CONTENT_TYPE);
                response.setHeader(RETRY_AFTER_HEADER, String.valueOf(retryAfterSeconds));
                response.getWriter().write(RATE_LIMIT_RESPONSE_TEMPLATE);
                return true;
            }

            // 记录本次请求时间戳
            timestamps.addLast(now);
            return false;
        }
    }

    /**
     * 全量清理过期的 IP 条目
     * 移除超过 STALE_ENTRY_TTL_MS 未产生新请求的 IP 记录，防止 ConcurrentHashMap 无限增长
     *
     * @param now 当前时间戳（毫秒）
     */
    private void cleanupStaleEntries(long now) {
        long staleThreshold = now - STALE_ENTRY_TTL_MS;
        Iterator<Map.Entry<String, LinkedList<Long>>> entryIterator = requestRecords.entrySet().iterator();
        int removedCount = 0;

        while (entryIterator.hasNext()) {
            Map.Entry<String, LinkedList<Long>> entry = entryIterator.next();
            LinkedList<Long> timestamps = entry.getValue();

            synchronized (timestamps) {
                // 如果该 IP 的所有请求记录都已过期，直接移除整个条目
                if (timestamps.isEmpty() || timestamps.getLast() < staleThreshold) {
                    entryIterator.remove();
                    removedCount++;
                    continue;
                }
                // 即使条目本身未过期，也清理其中的过期时间戳
                long windowStart = now - WINDOW_MS;
                Iterator<Long> tsIterator = timestamps.iterator();
                while (tsIterator.hasNext()) {
                    if (tsIterator.next() < windowStart) {
                        tsIterator.remove();
                    } else {
                        break;
                    }
                }
            }
        }

        if (removedCount > 0) {
            log.debug("速率限制器清理了 {} 个过期 IP 条目", removedCount);
        }
    }
}
