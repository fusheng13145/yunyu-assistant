package com.leyon.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 速率限制的计数单点（v2.45 从 {@code RateLimitInterceptor} 抽出）
 * <p>
 * 抽出来的理由不是"整洁"，而是**握手链路根本不经过 MVC 拦截器**：{@code /api/open/ws-voice/*} 的
 * 升级请求由 {@code HandshakeInterceptor} 处理，Spring 的 {@code HandlerInterceptor} 对它不生效，
 * 于是"把端点加进 Tier 的路径模式"这件事对语音握手完全无效（v2.44 之前的真实缺口）。如果不在这里
 * 共用一份计数，就得在握手拦截器里再写一遍内存滑窗 + Redis 固定窗口 + 降级逻辑——两处实现迟早漂移，
 * 而漂移的表现是"某个入口悄悄不限流"。
 * <p>
 * 本类只做"这一桶还能不能再放一次"的判定，**不写响应**：429 的报文属于 HTTP 语义，握手的拒绝属于
 * 握手状态码，两个调用方各自决定怎么把"超限"说出去。
 * <p>
 * 桶键为 {@code 客户端地址|档位}，地址必须是 {@code ClientIpResolver} 产出的可信来源（v2.44）；
 * 档位名参与键名，档与档互不干扰。启用 Redis（{@code app.redis.enabled=true}）时按同键做固定窗口
 * 共享计数（Redis key 前缀 {@code rate:} 自 v2.35 起未变，故滚动发布期间新旧实例仍共桶），
 * Redis 故障自动降级内存滑窗。
 *
 * @author leyon
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    /**
     * 限流分档。
     * AUTH＝凭证获取/变更端点共用 5 次/分钟/来源（防凭证爆破）；
     * EXPENSIVE＝消耗外部额度或带宽的端点共用 30 次/分钟/来源（正常单用户远达不到）；
     * OPEN_WS＝开放平台语音握手 10 次/分钟/来源（v2.45）。
     * <p>
     * 阈值 10 而不是照抄 AUTH 的 5：握手是页面级动作，重连与多端会成串发生，5 容易误伤真实集成；
     * 但它仍然把"拿握手接口暴力试 API Key"的预算压到 600 次/小时/来源，因为限流发生在验 key 之前。
     * <p>
     * {@code patterns} 为空表示该档不经 MVC 拦截器（由握手拦截器自行按档计数），
     * 因此 {@link #resolveMvc(String)} 永远不会命中它——避免"加了档就以为限了流"的错觉。
     */
    public enum Tier {
        AUTH(5, "/api/auth/login", "/api/auth/register", "/api/auth/refresh", "/api/auth/password"),
        EXPENSIVE(30, "/api/open/chat", "/api/open/call", "/api/ragflow/retrieval-test",
                "/api/call-records/*/recording"),
        OPEN_WS(10);

        /** 时间窗口内允许的最大请求次数 */
        private final int capacity;
        /** Ant 风格路径模式（无通配符的即精确匹配）；空数组＝不经 MVC 判定 */
        private final String[] patterns;

        Tier(int capacity, String... patterns) {
            this.capacity = capacity;
            this.patterns = patterns;
        }

        /** 该档在窗口内允许的次数 */
        public int capacity() {
            return capacity;
        }

        /** 该档在 MVC 侧的路径模式；空数组＝不经 MVC 拦截器（握手档） */
        public String[] patternsForMvc() {
            return patterns.clone();
        }

        /** 判定 URI 属于哪个"经 MVC 的档"；不命中任何档（普通业务路径或握手）返回 null */
        public static Tier resolveMvc(String uri) {
            for (Tier tier : values()) {
                for (String pattern : tier.patterns) {
                    if (PATH_MATCHER.match(pattern, uri)) {
                        return tier;
                    }
                }
            }
            return null;
        }

        /**
         * 全部经 MVC 档的路径模式，供 {@code AppConfig} 注册拦截器时使用。
         * <p>
         * 之所以由档位表提供而不是在注册处再抄一遍：漏注册的路径根本进不了拦截器，
         * 而表现为"这个端点居然不限流"——两份清单迟早漂移，不如只有一份。
         * 不含 {@link #OPEN_WS}（它的 patterns 本就为空，握手不经 MVC）。
         */
        public static String[] mvcPathPatterns() {
            return java.util.Arrays.stream(values())
                    .flatMap(tier -> java.util.Arrays.stream(tier.patterns))
                    .toArray(String[]::new);
        }
    }

    private static final org.springframework.util.AntPathMatcher PATH_MATCHER =
            new org.springframework.util.AntPathMatcher();

    /** 时间窗口大小（毫秒）：1 分钟 */
    public static final long WINDOW_MS = 60_000L;

    /** Redis key 前缀（自 v2.35 未变） */
    private static final String KEY_PREFIX = "rate:";

    /**
     * 请求记录：桶键 → 请求时间戳队列（毫秒）。
     * 既是 Redis 不可用时的降级实现，也是未启用 Redis 时的唯一实现（单实例）
     */
    private final Map<String, LinkedList<Long>> requestRecords = new ConcurrentHashMap<>();

    /** 可选注入：启用 Redis 后端时使用 */
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /** 是否启用 Redis 共享状态（环境变量 REDIS_ENABLED） */
    @Value("${app.redis.enabled:false}")
    private boolean redisEnabled;

    /** 上次全量清理时间戳，用于节流清理频率 */
    private volatile long lastCleanupTime = System.currentTimeMillis();

    /** 全量清理间隔（毫秒）：每 60 秒最多执行一次全量扫描 */
    private static final long CLEANUP_INTERVAL_MS = 60_000L;

    /** 过期条目存活时间（毫秒）：5 分钟无请求后视为过期 */
    private static final long STALE_ENTRY_TTL_MS = 5 * 60_000L;

    /** 判定结果：allowed=false 时 retryAfterSeconds 为建议的重试等待秒数（≥1） */
    public record Decision(boolean allowed, long retryAfterSeconds) {

        private static final Decision ALLOWED = new Decision(true, 0L);

        static Decision allow() {
            return ALLOWED;
        }

        static Decision reject(long retryAfterSeconds) {
            return new Decision(false, Math.max(1L, retryAfterSeconds));
        }
    }

    /**
     * 申请一次额度。
     *
     * @param tier     限流档
     * @param clientIp 可信来源地址（{@code ClientIpResolver} 的产物）
     * @return 放行或超限（超限时带 Retry-After 建议值）
     */
    public Decision tryAcquire(Tier tier, String clientIp) {
        String bucketKey = clientIp + "|" + tier.name();
        Decision redis = redisTryAcquire(bucketKey, tier);
        return redis != null ? redis : memoryTryAcquire(bucketKey, tier);
    }

    /** Redis 固定窗口计数；返回 null 表示未启用或失败，需降级内存 */
    private Decision redisTryAcquire(String bucketKey, Tier tier) {
        if (!redisEnabled || redisTemplate == null) {
            return null;
        }
        try {
            String key = KEY_PREFIX + bucketKey;
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, WINDOW_MS, TimeUnit.MILLISECONDS);
            }
            if (count != null && count > tier.capacity()) {
                // 固定窗口拿不到"窗口何时滚动"的精确剩余（TTL 可查但多一次往返），按整窗回退
                return Decision.reject(WINDOW_MS / 1000);
            }
            return Decision.allow();
        } catch (Exception e) {
            log.warn("Redis 限流失败，降级内存：{}", e.getMessage());
            return null;
        }
    }

    /** 内存滑动窗口计数（单实例实现 / Redis 降级实现） */
    private Decision memoryTryAcquire(String bucketKey, Tier tier) {
        long now = System.currentTimeMillis();
        if (now - lastCleanupTime > CLEANUP_INTERVAL_MS) {
            cleanupStaleEntries(now);
            lastCleanupTime = now;
        }
        LinkedList<Long> timestamps = requestRecords.computeIfAbsent(bucketKey, k -> new LinkedList<>());
        synchronized (timestamps) {
            long windowStart = now - WINDOW_MS;
            Iterator<Long> iterator = timestamps.iterator();
            while (iterator.hasNext()) {
                if (iterator.next() < windowStart) {
                    iterator.remove();
                } else {
                    // 队列按时间顺序排列，遇到第一条未过期的即可停止
                    break;
                }
            }
            if (timestamps.size() >= tier.capacity()) {
                long oldestInWindow = timestamps.getFirst();
                return Decision.reject((oldestInWindow + WINDOW_MS - now + 999) / 1000);
            }
            timestamps.addLast(now);
            return Decision.allow();
        }
    }

    /**
     * 全量清理过期条目：移除长时间无请求的桶，防止 ConcurrentHashMap 无限增长
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
                if (timestamps.isEmpty() || timestamps.getLast() < staleThreshold) {
                    entryIterator.remove();
                    removedCount++;
                    continue;
                }
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
            log.debug("速率限制器清理了 {} 个过期条目", removedCount);
        }
    }
}
