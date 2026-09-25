package com.leyon.backend.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 接口速率限制拦截器（v2.35 扩面为两档）
 * 认证桶：login/register/refresh/password 共用 5 次/分钟/IP（防凭证爆破）
 * 高成本桶：OpenAPI 对话/外呼、RAGFlow 检索试验、录音上传/下载共用 30 次/分钟/IP（防额度与带宽滥用）
 * 配置 app.redis.enabled=true 时使用 Redis 固定窗口计数（多实例共享）；否则使用内存滑动窗口（单实例）
 * Redis 故障时自动降级内存实现
 *
 * @author leyon
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    /**
     * 请求记录：IP+档位 → 请求时间戳队列（毫秒）
     * 使用 ConcurrentHashMap 保证线程安全（内存降级实现）
     */
    private final Map<String, LinkedList<Long>> requestRecords = new ConcurrentHashMap<>();

    /** Redis key 前缀 */
    private static final String KEY_PREFIX = "rate:";

    /** 可选注入：启用 Redis 后端时使用 */
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /** 是否启用 Redis 共享状态（环境变量 REDIS_ENABLED） */
    @Value("${app.redis.enabled:false}")
    private boolean redisEnabled;

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
     * 限流分档（v2.35 扩面）：
     * AUTH＝凭证获取/变更端点共用一个 5 次/分钟 的桶（与 v2.35 前"login+register 共桶"口径一致，
     * 新增 refresh/password 只是并入同桶，不摊薄既有爆破防护）；
     * EXPENSIVE＝消耗外部额度或带宽的端点共用 30 次/分钟 的桶（正常单用户远达不到）。
     * 同一 IP 同一档共享计数；档与档互不干扰。
     */
    private enum Tier {
        AUTH(5, "/api/auth/login", "/api/auth/register", "/api/auth/refresh", "/api/auth/password"),
        EXPENSIVE(30, "/api/open/chat", "/api/open/call", "/api/ragflow/retrieval-test",
                "/api/call-records/*/recording");

        /** 时间窗口内允许的最大请求次数 */
        private final int capacity;
        /** Ant 风格路径模式（无通配符的即精确匹配） */
        private final String[] patterns;

        Tier(int capacity, String... patterns) {
            this.capacity = capacity;
            this.patterns = patterns;
        }

        /** 判定 URI 属于哪一档；不属于任何档（普通业务路径）返回 null */
        private static Tier resolve(String uri) {
            for (Tier tier : values()) {
                for (String pattern : tier.patterns) {
                    if (PATH_MATCHER.match(pattern, uri)) {
                        return tier;
                    }
                }
            }
            return null;
        }
    }

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

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

        // 仅对分档内的路径进行速率限制（v2.35：认证桶 + 高成本桶）
        String requestUri = request.getRequestURI();
        Tier tier = Tier.resolve(requestUri);
        if (tier == null) {
            return true;
        }

        // 获取客户端真实IP（优先 X-Forwarded-For，其次 RemoteAddr），桶按 IP+档位 计
        String bucketKey = getClientIp(request) + "|" + tier.name();

        // 检查是否超出速率限制（Redis 优先，失败降级内存）
        if (isRateLimited(bucketKey, tier, response)) {
            return false;
        }

        return true;
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
     * 执行速率限制检查：Redis 固定窗口优先，异常时降级内存滑动窗口
     *
     * @param bucketKey IP+档位 组合键
     * @param tier      命中的限流档
     * @param response  响应对象（用于设置429响应头）
     * @return true-已限流需拦截，false-未超限可放行
     */
    private boolean isRateLimited(String bucketKey, Tier tier, HttpServletResponse response) throws IOException {
        Boolean redisLimited = redisRateLimit(bucketKey, tier, response);
        if (redisLimited != null) {
            return redisLimited;
        }
        return memoryRateLimit(bucketKey, tier, response);
    }

    /** Redis 固定窗口限流；返回 null 表示未启用/失败需降级内存 */
    private Boolean redisRateLimit(String bucketKey, Tier tier, HttpServletResponse response) throws IOException {
        if (!redisEnabled || redisTemplate == null) {
            return null;
        }
        try {
            String key = KEY_PREFIX + bucketKey;
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, WINDOW_MS, TimeUnit.MILLISECONDS);
            }
            if (count != null && count > tier.capacity) {
                writeRateLimitResponse(response);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("Redis 限流失败，降级内存：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 内存滑动窗口限流（单实例降级实现）
     */
    private boolean memoryRateLimit(String bucketKey, Tier tier, HttpServletResponse response) throws IOException {
        long now = System.currentTimeMillis();

        // 惰性全量清理：每隔 CLEANUP_INTERVAL_MS 扫描一次，移除长时间无请求的过期 IP 条目
        if (now - lastCleanupTime > CLEANUP_INTERVAL_MS) {
            cleanupStaleEntries(now);
            lastCleanupTime = now;
        }

        // 获取或创建该 IP+档位 的请求记录队列
        LinkedList<Long> timestamps = requestRecords.computeIfAbsent(
                bucketKey, k -> new LinkedList<>()
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
            if (timestamps.size() >= tier.capacity) {
                // 计算剩余等待时间（秒）
                long oldestInWindow = timestamps.getFirst();
                long retryAfterSeconds = Math.max(1, (oldestInWindow + WINDOW_MS - now + 999) / 1000);

                // 设置 429 响应头和状态码
                writeRateLimitResponse(response, retryAfterSeconds);
                return true;
            }

            // 记录本次请求时间戳
            timestamps.addLast(now);
            return false;
        }
    }

    /** 写入 429 响应（默认 Retry-After 由窗口剩余时间计算） */
    private void writeRateLimitResponse(HttpServletResponse response) throws IOException {
        writeRateLimitResponse(response, Math.max(1, WINDOW_MS / 1000));
    }

    /** 写入 429 响应（自定义 Retry-After） */
    private void writeRateLimitResponse(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(TOO_MANY_REQUESTS_CODE);
        response.setContentType(JSON_CONTENT_TYPE);
        response.setHeader(RETRY_AFTER_HEADER, String.valueOf(retryAfterSeconds));
        response.getWriter().write(RATE_LIMIT_RESPONSE_TEMPLATE);
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
