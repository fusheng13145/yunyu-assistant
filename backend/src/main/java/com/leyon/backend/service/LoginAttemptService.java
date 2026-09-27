package com.leyon.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 登录失败计数与临时锁定服务（防撞库）
 * <p>
 * v2.44 起按「两个维度」并行判定，因为单靠"用户名维度"会把防御武器变成 DoS 工具：
 * <ul>
 *   <li>{@link LoginLockTarget#username(String)}——账号兜底：跨 IP 的慢速密码撞库靠它收口。
 *       阈值自 5 次/15 分钟放宽到 15 次/5 分钟（可配），代价是"匿名把指定账号锁在门外"的
 *       杀伤面变小，而快速爆破本来就由认证限流（每客户端 5 次/分钟）挡在前面。</li>
 *   <li>{@link LoginLockTarget#ip(String)}——来源主判定：单一来源的持续尝试在它自己的额度上
 *       累计，与换了用户名无关；攻击者无法通过轮换用户名绕开。</li>
 * </ul>
 * 两个维度各自独立计数、独立锁定，任一锁定即拒绝登录；登录成功同时清除两个维度。
 * <p>
 * 桶键里的"IP"必须是 {@code ClientIpResolver} 给出的可信来源地址——如果代理头还能生效，
 * 来源维度就等于没有。默认（未启用反代信任）取连接层地址。
 * <p>
 * 默认基于内存（单实例）；配置 app.redis.enabled=true 时改用 Redis（多实例共享），
 * Redis 故障时自动降级内存实现。
 *
 * @author leyon
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    /**
     * 锁定维度：账号兜底 与 来源主判定（v2.44）。
     * 阈值与锁定时长绑在维度上而不是散在方法里，避免出现"IP 用了账号的阈值"这类漂移。
     */
    public enum Dimension {
        /** 账号维度：跨来源的慢速撞库兜底 */
        USERNAME("user:", 15, 5 * 60_000L),
        /** 来源维度：单 IP 的持续尝试主判定，阈值高但打击面精准 */
        IP("ip:", 20, 15 * 60_000L);

        private final String keyPrefix;
        /** 该维度的默认阈值（账号维度可被配置覆盖，见 resolveThreshold） */
        private final int defaultThreshold;
        private final long lockDurationMs;

        Dimension(String keyPrefix, int defaultThreshold, long lockDurationMs) {
            this.keyPrefix = keyPrefix;
            this.defaultThreshold = defaultThreshold;
            this.lockDurationMs = lockDurationMs;
        }

        /** 该维度的锁定时长（毫秒） */
        long lockDurationMs() {
            return lockDurationMs;
        }
    }

    /**
     * 锁定目标：维度 + 原始值。原始值为空时 {@link #storageKey()} 返回 null，调用方一律忽略
     * （不产生"空键共享一桶"）。
     */
    public static final class LoginLockTarget {
        private final Dimension dimension;
        private final String rawValue;

        private LoginLockTarget(Dimension dimension, String rawValue) {
            this.dimension = dimension;
            this.rawValue = rawValue;
        }

        /** 账号维度目标 */
        public static LoginLockTarget username(String username) {
            return new LoginLockTarget(Dimension.USERNAME, username);
        }

        /** 来源维度目标；入参必须是 ClientIpResolver 产出的可信地址 */
        public static LoginLockTarget ip(String clientIp) {
            return new LoginLockTarget(Dimension.IP, clientIp);
        }

        /** Redis/内存Map 用的键后缀（含维度前缀）；无有效值返回 null */
        String storageKey() {
            if (!StringUtils.hasText(rawValue)) {
                return null;
            }
            return dimension.keyPrefix + rawValue.trim();
        }
    }

    /** 锁定尝试记录 */
    private static final class Attempt {
        int failCount;
        long lockedUntil; // 锁定截止时间戳（毫秒），0 表示未锁定

        Attempt() {
            this.failCount = 0;
            this.lockedUntil = 0L;
        }
    }

    /** 目标键 -> 尝试记录（内存降级实现） */
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /** Redis key 前缀（维度前缀再叠在其后，保持与 v2.43 之前已存键的兼容） */
    private static final String KEY_FAIL_PREFIX = "auth:fail:";
    private static final String KEY_LOCK_PREFIX = "auth:locked:";

    /** 可选注入：启用 Redis 后端时使用 */
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /** 是否启用 Redis 共享状态（环境变量 REDIS_ENABLED） */
    @Value("${app.redis.enabled:false}")
    private boolean redisEnabled;

    /** 账号维度阈值（环境变量 LOGIN_LOCK_USERNAME_FAILURES，0/负数回默认 15） */
    @Value("${app.security.login-lock.username-failures:15}")
    private int usernameFailures;

    /** 上次清理时间戳，节流清理 */
    private volatile long lastCleanupTime = System.currentTimeMillis();

    /** 清理间隔（毫秒） */
    private static final long CLEANUP_INTERVAL_MS = 60_000L;

    /**
     * 判定用的阈值一律经此处取值：配置非正数视为未配置，回落该维度默认值
     * （不把枚举字段改掉，避免进程级可变状态）
     */
    private int resolveThreshold(Dimension dimension) {
        if (dimension == Dimension.USERNAME && usernameFailures > 0) {
            return usernameFailures;
        }
        return dimension.defaultThreshold;
    }

    /**
     * 记录一次登录失败；达到该维度阈值后锁定
     *
     * @param target 锁定目标（账号或来源维度）
     * @return 锁定剩余毫秒数；0 表示尚未触发锁定
     */
    public long recordFailure(LoginLockTarget target) {
        String key = target == null ? null : target.storageKey();
        if (key == null) {
            return 0L;
        }
        Long redisLockMs = redisRecordFailure(key, target.dimension);
        if (redisLockMs != null) {
            return redisLockMs;
        }
        maybeCleanup();
        long now = System.currentTimeMillis();
        Attempt attempt = attempts.computeIfAbsent(key, k -> new Attempt());
        synchronized (attempt) {
            // 若已锁定且仍在锁定期内，直接返回剩余时间（不累加，避免"越试越久"）
            if (attempt.lockedUntil > now) {
                return attempt.lockedUntil - now;
            }
            attempt.failCount++;
            if (attempt.failCount >= resolveThreshold(target.dimension)) {
                attempt.lockedUntil = now + target.dimension.lockDurationMs();
                attempt.failCount = 0;
                return target.dimension.lockDurationMs();
            }
            return 0L;
        }
    }

    /**
     * 登录成功后清除失败计数（两个维度一起清：本次成功的账号与来源都不该背着旧账）
     *
     * @param targets 本次登录涉及的锁定目标，null 元素忽略
     */
    public void recordSuccess(LoginLockTarget... targets) {
        if (targets == null) {
            return;
        }
        for (LoginLockTarget target : targets) {
            String key = target == null ? null : target.storageKey();
            if (key == null) {
                continue;
            }
            if (redisEnabled && redisTemplate != null) {
                try {
                    redisTemplate.delete(KEY_FAIL_PREFIX + key);
                    redisTemplate.delete(KEY_LOCK_PREFIX + key);
                    continue;
                } catch (Exception e) {
                    log.warn("Redis 清除登录计数失败，降级内存：{}", e.getMessage());
                }
            }
            attempts.remove(key);
        }
    }

    /**
     * 检查目标当前是否被锁定
     *
     * @param target 锁定目标
     * @return 锁定剩余毫秒数；0 表示未锁定
     */
    public long getRemainingLockMs(LoginLockTarget target) {
        String key = target == null ? null : target.storageKey();
        if (key == null) {
            return 0L;
        }
        Long redisLockMs = redisGetRemainingLockMs(key);
        if (redisLockMs != null) {
            return redisLockMs;
        }
        maybeCleanup();
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return 0L;
        }
        synchronized (attempt) {
            // 仅当处于锁定状态且已过期时才清理，未锁定条目不删除（保留失败计数）
            if (attempt.lockedUntil > 0 && attempt.lockedUntil <= System.currentTimeMillis()) {
                attempts.remove(key);
                return 0L;
            }
            if (attempt.lockedUntil <= 0) {
                return 0L;
            }
            return attempt.lockedUntil - System.currentTimeMillis();
        }
    }

    /** Redis 记录失败；返回 null 表示未启用/失败需降级内存 */
    private Long redisRecordFailure(String key, Dimension dimension) {
        if (!redisEnabled || redisTemplate == null) {
            return null;
        }
        try {
            String failKey = KEY_FAIL_PREFIX + key;
            Long count = redisTemplate.opsForValue().increment(failKey);
            if (count != null && count == 1L) {
                redisTemplate.expire(failKey, dimension.lockDurationMs(), TimeUnit.MILLISECONDS);
            }
            // 已锁定直接返回剩余时间
            Long lockTtl = redisTemplate.getExpire(KEY_LOCK_PREFIX + key, TimeUnit.MILLISECONDS);
            if (lockTtl != null && lockTtl > 0) {
                return lockTtl;
            }
            if (count != null && count >= resolveThreshold(dimension)) {
                redisTemplate.opsForValue().set(KEY_LOCK_PREFIX + key, "1",
                        dimension.lockDurationMs(), TimeUnit.MILLISECONDS);
                redisTemplate.delete(failKey);
                return dimension.lockDurationMs();
            }
            return 0L;
        } catch (Exception e) {
            log.warn("Redis 记录登录失败失败，降级内存：{}", e.getMessage());
            return null;
        }
    }

    /** Redis 查询锁定剩余；返回 null 表示未启用/失败需降级内存 */
    private Long redisGetRemainingLockMs(String key) {
        if (!redisEnabled || redisTemplate == null) {
            return null;
        }
        try {
            Long lockTtl = redisTemplate.getExpire(KEY_LOCK_PREFIX + key, TimeUnit.MILLISECONDS);
            return (lockTtl != null && lockTtl > 0) ? lockTtl : 0L;
        } catch (Exception e) {
            log.warn("Redis 查询登录锁定失败，降级内存：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 惰性清理过期条目（锁定已过期或长期无记录的条目）
     */
    private void maybeCleanup() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupTime < CLEANUP_INTERVAL_MS) {
            return;
        }
        lastCleanupTime = now;
        Iterator<Map.Entry<String, Attempt>> iterator = attempts.entrySet().iterator();
        while (iterator.hasNext()) {
            Attempt attempt = iterator.next().getValue();
            synchronized (attempt) {
                // 各维度锁定时长不同，用最长的那个作清理宽限即可（只会晚清，不会早清）
                long graceMs = Math.max(Dimension.USERNAME.lockDurationMs(), Dimension.IP.lockDurationMs());
                if (attempt.lockedUntil > 0 && attempt.lockedUntil < now - graceMs) {
                    iterator.remove();
                } else if (attempt.lockedUntil == 0 && attempt.failCount == 0) {
                    iterator.remove();
                }
            }
        }
    }
}
