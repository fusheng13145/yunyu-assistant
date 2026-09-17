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
 * JWT 令牌黑名单服务（登出失效）
 * 默认基于内存（单实例）；配置 app.redis.enabled=true 时改用 Redis（多实例共享，jti 带 TTL 自动过期）
 * Redis 故障时自动降级内存实现，不影响登出可用性
 *
 * @author leyon
 */
@Service
public class TokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistService.class);

    /** 黑名单：jti -> 过期时间戳（毫秒），超过后自动失效并清理（内存降级实现） */
    private final Map<String, Long> blacklist = new ConcurrentHashMap<>();

    /** Redis key 前缀 */
    private static final String KEY_PREFIX = "auth:blacklist:";

    /** 可选注入：启用 Redis 后端时使用 */
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /** 是否启用 Redis 共享状态（环境变量 REDIS_ENABLED） */
    @Value("${app.redis.enabled:false}")
    private boolean redisEnabled;

    /** 上次全量清理时间戳（毫秒），用于节流清理频率 */
    private volatile long lastCleanupTime = System.currentTimeMillis();

    /** 全量清理间隔（毫秒）：每 60 秒最多执行一次 */
    private static final long CLEANUP_INTERVAL_MS = 60_000L;

    /** 黑名单条目最长存活时间（毫秒）：10 分钟（覆盖最长 refresh token 生命周期） */
    private static final long ENTRY_TTL_MS = 10 * 60_000L;

    /**
     * 将令牌加入黑名单
     *
     * @param jti  令牌唯一标识（JWT claim）
     * @param ttlMs 黑名单有效时长（毫秒），保证条目在令牌自然过期前保持有效
     */
    public void blacklist(String jti, long ttlMs) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        long safeTtl = Math.max(ttlMs, 1000L);
        if (tryRedisBlacklist(jti, safeTtl)) {
            return;
        }
        long expireAt = System.currentTimeMillis() + safeTtl;
        blacklist.put(jti, expireAt);
        log.info("令牌已加入黑名单（内存），jti:{}", jti);
    }

    /**
     * 判断令牌是否已被拉黑
     *
     * @param jti 令牌唯一标识
     * @return true-已拉黑或已过期清理 false-有效
     */
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        if (redisEnabled) {
            Boolean exists = redisHasKey(KEY_PREFIX + jti);
            if (exists != null) {
                return exists;
            }
        }
        maybeCleanup();
        Long expireAt = blacklist.get(jti);
        if (expireAt == null) {
            return false;
        }
        if (expireAt < System.currentTimeMillis()) {
            blacklist.remove(jti);
            return false;
        }
        return true;
    }

    /** Redis 写入黑名单；返回 true 表示成功使用 Redis，false 表示未启用/失败需降级内存 */
    private boolean tryRedisBlacklist(String jti, long ttlMs) {
        if (!redisEnabled || redisTemplate == null) {
            return false;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", ttlMs, TimeUnit.MILLISECONDS);
            log.info("令牌已加入黑名单（Redis），jti:{}", jti);
            return true;
        } catch (Exception e) {
            log.warn("Redis 黑名单写入失败，降级内存：{}", e.getMessage());
            return false;
        }
    }

    /** Redis 查询黑名单；返回 null 表示未启用/失败（由调用方降级内存） */
    private Boolean redisHasKey(String key) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.warn("Redis 黑名单查询失败，降级内存：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 惰性全量清理：每隔 CLEANUP_INTERVAL_MS 清理一次已过期的黑名单条目
     */
    private void maybeCleanup() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupTime < CLEANUP_INTERVAL_MS) {
            return;
        }
        lastCleanupTime = now;
        Iterator<Map.Entry<String, Long>> iterator = blacklist.entrySet().iterator();
        int removed = 0;
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (entry.getValue() < now - ENTRY_TTL_MS || entry.getValue() < now) {
                iterator.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("令牌黑名单清理了 {} 个过期条目", removed);
        }
    }
}