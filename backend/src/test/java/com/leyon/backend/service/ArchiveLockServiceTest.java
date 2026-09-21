package com.leyon.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据归档分布式锁服务单元测试（v2.19 跨实例防重）
 * 覆盖：Redis setIfAbsent true 放行 / false 拒绝 / 异常降级内存锁、内存锁重入拦截、release 后放行、Redis 释放比对
 *
 * @author leyon
 */
class ArchiveLockServiceTest {

    private ArchiveLockService lockService;
    private ArchiveProperties props;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    void setUp() throws Exception {
        props = new ArchiveProperties();
        props.setLockTtlMs(600_000);
        redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        valueOps = ops;
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lockService = new ArchiveLockService(props);
        setField("redisTemplate", redisTemplate);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = ArchiveLockService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(lockService, value);
    }

    private void setRedisEnabled(boolean enabled) throws Exception {
        setField("redisEnabled", enabled);
    }

    @Test
    void redisEnabled_setIfAbsentTrue_acquires() throws Exception {
        setRedisEnabled(true);
        when(valueOps.setIfAbsent(eq("yunyu:archive:lock"), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        assertThat(lockService.tryAcquire()).isTrue();
    }

    @Test
    void redisEnabled_setIfAbsentFalse_rejects() throws Exception {
        setRedisEnabled(true);
        when(valueOps.setIfAbsent(eq("yunyu:archive:lock"), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);
        assertThat(lockService.tryAcquire()).isFalse();
    }

    @Test
    void redisError_fallsBackToMemoryLock() throws Exception {
        setRedisEnabled(true);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new RuntimeException("redis down"));
        // 第一次降级内存取锁成功
        assertThat(lockService.tryAcquire()).isTrue();
        // 未释放前重入被拦截
        assertThat(lockService.tryAcquire()).isFalse();
        // 释放后可再取
        lockService.release();
        assertThat(lockService.tryAcquire()).isTrue();
    }

    @Test
    void redisDisabled_usesMemoryLock_reentrantBlocked() throws Exception {
        setRedisEnabled(false);
        assertThat(lockService.tryAcquire()).isTrue();
        assertThat(lockService.tryAcquire()).isFalse();
        lockService.release();
        assertThat(lockService.tryAcquire()).isTrue();
    }

    @Test
    void release_redisHolderMismatch_doesNotDelete() throws Exception {
        setRedisEnabled(true);
        when(valueOps.get("yunyu:archive:lock")).thenReturn("other-instance");
        lockService.release();
        verify(redisTemplate, never()).delete("yunyu:archive:lock");
    }
}