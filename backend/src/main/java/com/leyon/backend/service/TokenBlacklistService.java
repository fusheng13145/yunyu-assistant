package com.leyon.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JWT 令牌黑名单服务（登出失效）
 * 基于内存的 jti 黑名单：登出时按令牌 jti 加入黑名单，校验时命中即视为无效
 * 采用惰性清理过期条目，防止内存无限增长
 *
 * 说明：当前为单实例内存实现；多实例部署时需替换为 Redis（见手册 6.6 迭代方向）
 *
 * @author leyon
 */
@Service
public class TokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistService.class);

    /** 黑名单：jti -> 过期时间戳（毫秒），超过后自动失效并清理 */
    private final Map<String, Long> blacklist = new ConcurrentHashMap<>();

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
        long expireAt = System.currentTimeMillis() + Math.max(ttlMs, 1000L);
        blacklist.put(jti, expireAt);
        log.info("令牌已加入黑名单，jti:{}", jti);
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