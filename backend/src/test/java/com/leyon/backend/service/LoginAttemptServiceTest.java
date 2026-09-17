package com.leyon.backend.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录失败锁定单元测试
 * 覆盖：连续失败触发锁定、锁定期间拒绝、成功后清除计数
 *
 * @author leyon
 */
class LoginAttemptServiceTest {

    private final LoginAttemptService service = new LoginAttemptService();

    @Test
    void failuresBelowThreshold_notLocked() {
        for (int i = 0; i < 4; i++) {
            assertThat(service.recordFailure("alice")).isZero();
        }
        assertThat(service.getRemainingLockMs("alice")).isZero();
    }

    @Test
    void fifthFailure_triggersLock() {
        for (int i = 0; i < 4; i++) {
            service.recordFailure("alice");
        }
        long lockMs = service.recordFailure("alice");
        assertThat(lockMs).isGreaterThan(0);
        assertThat(service.getRemainingLockMs("alice")).isGreaterThan(0);
    }

    @Test
    void success_clearsFailureCount() {
        for (int i = 0; i < 4; i++) {
            service.recordFailure("bob");
        }
        service.recordSuccess("bob");
        // 清除后下一次失败从 1 开始计数，不会立即锁定
        assertThat(service.recordFailure("bob")).isZero();
        assertThat(service.getRemainingLockMs("bob")).isZero();
    }

    @Test
    void lockedUser_remainsLockedUntilExpiry() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("carol");
        }
        // 锁定持续生效（锁定期内多次查询仍返回剩余时间）
        assertThat(service.getRemainingLockMs("carol")).isGreaterThan(0);
        assertThat(service.getRemainingLockMs("carol")).isGreaterThan(0);
        // 锁定期内继续失败不会延长到无穷（仍基于同一截止时间）
        service.recordFailure("carol");
        assertThat(service.getRemainingLockMs("carol")).isGreaterThan(0);
    }

    @Test
    void blankUsername_ignored() {
        assertThat(service.recordFailure("")).isZero();
        assertThat(service.recordFailure(null)).isZero();
        assertThat(service.getRemainingLockMs("")).isZero();
    }
}