package com.leyon.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 登录失败计数与账号临时锁定服务（防撞库）
 * 同一用户名连续失败超过阈值后临时锁定，锁定期间即使密码正确也拒绝登录
 * 默认基于内存（单实例）；配置 app.redis.enabled=true 时改用 Redis（多实例共享）
 * Redis 故障时自动降级内存实现
 *
 * @author leyon
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    /** 锁定尝试记录 */
    private static final class Attempt {
        int failCount;
        long lockedUntil; // 锁定截止时间戳（毫秒），0 表示未锁定

        Attempt() {
            this.failCount = 0;
            this.lockedUntil = 0L;
        }
    }

    /** 用户名 -> 尝试记录（内存降级实现） */
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /** Redis key 前缀 */
    private static final String KEY_FAIL_PREFIX = "auth:fail:";
    private static final String KEY_LOCK_PREFIX = "auth:locked:";

    /** 可选注入：启用 Redis 后端时使用 */
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /** 是否启用 Redis 共享状态（环境变量 REDIS_ENABLED） */
    @Value("${app.redis.enabled:false}")
    private boolean redisEnabled;

    /** 连续失败阈值：超过后锁定 */
    private static final int MAX_FAILURES = 5;

    /** 锁定时长（毫秒）：15 分钟 */
    private static final long LOCK_DURATION_MS = 15 * 60_000L;

    /** 上次清理时间戳，节流清理 */
    private volatile long lastCleanupTime = System.currentTimeMillis();

    /** 清理间隔（毫秒） */
    private static final long CLEANUP_INTERVAL_MS = 60_000L;

    /**
     * 记录一次登录失败，失败达到阈值后锁定
     *
     * @param username 用户名
     * @return 锁定剩余毫秒数；0 表示尚未触发锁定
     */
    public long recordFailure(String username) {
        if (username == null || username.isBlank()) {
            return 0L;
        }
        Long redisLockMs = redisRecordFailure(username);
        if (redisLockMs != null) {
            return redisLockMs;
        }
        maybeCleanup();
        long now = System.currentTimeMillis();
        Attempt attempt = attempts.computeIfAbsent(username, k -> new Attempt());
        synchronized (attempt) {
            // 若已锁定且仍在锁定期内，直接返回剩余时间
            if (attempt.lockedUntil > now) {
                return attempt.lockedUntil - now;
            }
            attempt.failCount++;
            if (attempt.failCount >= MAX_FAILURES) {
                attempt.lockedUntil = now + LOCK_DURATION_MS;
                attempt.failCount = 0;
                return LOCK_DURATION_MS;
            }
            return 0L;
        }
    }

    /**
     * 登录成功后清除失败计数
     *
     * @param username 用户名
     */
    public void recordSuccess(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        if (redisEnabled && redisTemplate != null) {
            try {
                redisTemplate.delete(KEY_FAIL_PREFIX + username);
                redisTemplate.delete(KEY_LOCK_PREFIX + username);
                return;
            } catch (Exception e) {
                log.warn("Redis 清除登录计数失败，降级内存：{}", e.getMessage());
            }
        }
        attempts.remove(username);
    }

    /**
     * 检查用户名当前是否被锁定
     *
     * @param username 用户名
     * @return 锁定剩余毫秒数；0 表示未锁定
     */
    public long getRemainingLockMs(String username) {
        if (username == null || username.isBlank()) {
            return 0L;
        }
        Long redisLockMs = redisGetRemainingLockMs(username);
        if (redisLockMs != null) {
            return redisLockMs;
        }
        maybeCleanup();
        Attempt attempt = attempts.get(username);
        if (attempt == null) {
            return 0L;
        }
        synchronized (attempt) {
            // 仅当处于锁定状态（lockedUntil > 0）且已过期时才清理，未锁定条目不删除（保留失败计数）
            if (attempt.lockedUntil > 0 && attempt.lockedUntil <= System.currentTimeMillis()) {
                attempts.remove(username);
                return 0L;
            }
            if (attempt.lockedUntil <= 0) {
                return 0L;
            }
            return attempt.lockedUntil - System.currentTimeMillis();
        }
    }

    /** Redis 记录失败；返回 null 表示未启用/失败需降级内存 */
    private Long redisRecordFailure(String username) {
        if (!redisEnabled || redisTemplate == null) {
            return null;
        }
        try {
            String failKey = KEY_FAIL_PREFIX + username;
            Long count = redisTemplate.opsForValue().increment(failKey);
            if (count != null && count == 1L) {
                redisTemplate.expire(failKey, LOCK_DURATION_MS, TimeUnit.MILLISECONDS);
            }
            // 已锁定直接返回剩余时间
            Long lockTtl = redisTemplate.getExpire(KEY_LOCK_PREFIX + username, TimeUnit.MILLISECONDS);
            if (lockTtl != null && lockTtl > 0) {
                return lockTtl;
            }
            if (count != null && count >= MAX_FAILURES) {
                redisTemplate.opsForValue().set(KEY_LOCK_PREFIX + username, "1", LOCK_DURATION_MS, TimeUnit.MILLISECONDS);
                redisTemplate.delete(failKey);
                return LOCK_DURATION_MS;
            }
            return 0L;
        } catch (Exception e) {
            log.warn("Redis 记录登录失败失败，降级内存：{}", e.getMessage());
            return null;
        }
    }

    /** Redis 查询锁定剩余；返回 null 表示未启用/失败需降级内存 */
    private Long redisGetRemainingLockMs(String username) {
        if (!redisEnabled || redisTemplate == null) {
            return null;
        }
        try {
            Long lockTtl = redisTemplate.getExpire(KEY_LOCK_PREFIX + username, TimeUnit.MILLISECONDS);
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
                if (attempt.lockedUntil > 0 && attempt.lockedUntil < now - LOCK_DURATION_MS) {
                    iterator.remove();
                } else if (attempt.lockedUntil == 0 && attempt.failCount == 0) {
                    iterator.remove();
                }
            }
        }
    }
}