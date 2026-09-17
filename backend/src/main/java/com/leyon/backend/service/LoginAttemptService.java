package com.leyon.backend.service;

import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败计数与账号临时锁定服务（防撞库）
 * 同一用户名连续失败超过阈值后临时锁定，锁定期间即使密码正确也拒绝登录
 * 基于内存实现（单实例），多实例部署时需替换为 Redis
 *
 * @author leyon
 */
@Service
public class LoginAttemptService {

    /** 锁定尝试记录 */
    private static final class Attempt {
        int failCount;
        long lockedUntil; // 锁定截止时间戳（毫秒），0 表示未锁定

        Attempt() {
            this.failCount = 0;
            this.lockedUntil = 0L;
        }
    }

    /** 用户名 -> 尝试记录 */
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

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