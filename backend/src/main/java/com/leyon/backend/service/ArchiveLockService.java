package com.leyon.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据归档分布式锁服务（v2.19 跨实例防重）
 * 多实例部署时定时归档仅由一个实例执行：
 * - Redis 启用（app.redis.enabled=true）且连接可用时使用 Redis SETNX 锁（key 带 TTL 自动过期），
 *   释放时比对持有者 instanceId 防误删他人锁；
 * - Redis 未启用或异常时降级单实例进程内内存锁（AtomicBoolean），保证单实例不重复执行。
 *
 * @author leyon
 */
@Service
public class ArchiveLockService {

    private static final Logger logger = LoggerFactory.getLogger(ArchiveLockService.class);

    /** 归档锁 Redis Key */
    private static final String KEY = "yunyu:archive:lock";

    /** 当前实例唯一标识（Redis 锁 value，释放时比对防误删） */
    private final String instanceId = UUID.randomUUID().toString();

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /** 是否启用 Redis 共享状态（环境变量 REDIS_ENABLED） */
    @Value("${app.redis.enabled:false}")
    private boolean redisEnabled;

    private final ArchiveProperties props;

    /** 内存锁（Redis 降级路径） */
    private final AtomicBoolean inMemoryLocked = new AtomicBoolean(false);

    public ArchiveLockService(ArchiveProperties props) {
        this.props = props;
    }

    /**
     * 尝试获取归档锁
     *
     * @return true-获取成功（本实例可执行归档）；false-已有其他实例持有，本次跳过
     */
    public boolean tryAcquire() {
        if (redisEnabled && redisTemplate != null) {
            try {
                Boolean ok = redisTemplate.opsForValue()
                        .setIfAbsent(KEY, instanceId, props.getLockTtlMs(), TimeUnit.MILLISECONDS);
                return Boolean.TRUE.equals(ok);
            } catch (Exception e) {
                logger.warn("Redis 归档锁获取异常，降级内存锁：{}", e.getMessage());
            }
        }
        // Redis 未启用/不可用：进程内内存锁（单实例防重）
        return inMemoryLocked.compareAndSet(false, true);
    }

    /**
     * 释放归档锁（内存锁直接释放；Redis 锁仅当仍由本实例持有时删除，防误删他人锁）
     */
    public void release() {
        inMemoryLocked.set(false);
        if (redisEnabled && redisTemplate != null) {
            try {
                String holder = redisTemplate.opsForValue().get(KEY);
                if (StringUtils.hasText(holder) && instanceId.equals(holder)) {
                    redisTemplate.delete(KEY);
                }
            } catch (Exception e) {
                logger.warn("Redis 归档锁释放异常（锁将随 TTL 自动过期）：{}", e.getMessage());
            }
        }
    }
}