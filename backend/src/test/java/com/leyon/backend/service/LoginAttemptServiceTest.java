package com.leyon.backend.service;

import com.leyon.backend.service.LoginAttemptService.LoginLockTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录失败锁定单元测试（v2.44 双维度）
 * <p>
 * 覆盖三件事：①来源维度按自己的阈值（20 次/15 分钟）锁定，与用户名无关；
 * ②账号维度放宽到 15 次/5 分钟且可配，因为它同时是"匿名锁人"的杀伤面；
 * ③两个维度各存各的键——同名用户名与 IP 不能互相顶掉计数。
 * 走内存实现（redisEnabled 默认 false）。
 *
 * @author leyon
 */
class LoginAttemptServiceTest {

    private final LoginAttemptService service = new LoginAttemptService();

    private static LoginLockTarget user(String name) {
        return LoginLockTarget.username(name);
    }

    private static LoginLockTarget ip(String addr) {
        return LoginLockTarget.ip(addr);
    }

    /** 连打 n 次失败，返回最后一次 recordFailure 的锁定剩余毫秒 */
    private long failTimes(LoginLockTarget target, int times) {
        return failTimes(service, target, times);
    }

    private static long failTimes(LoginAttemptService target, LoginLockTarget lockTarget, int times) {
        long last = 0L;
        for (int i = 0; i < times; i++) {
            last = target.recordFailure(lockTarget);
        }
        return last;
    }

    @Nested
    @DisplayName("来源维度：单 IP 的持续尝试主判定")
    class IpDimension {

        @Test
        @DisplayName("19 次不锁、第 20 次锁定 15 分钟")
        void twentiethFailureLocks() {
            assertThat(failTimes(ip("203.0.113.7"), 19)).isZero();
            assertThat(service.getRemainingLockMs(ip("203.0.113.7"))).isZero();

            long lockMs = service.recordFailure(ip("203.0.113.7"));
            assertThat(lockMs).isEqualTo(15 * 60_000L);
            assertThat(service.getRemainingLockMs(ip("203.0.113.7"))).isGreaterThan(0);
        }

        @Test
        @DisplayName("来源额度与用户名无关：换用户名也打在同一个 IP 键上")
        void ipBucketIgnoresWhichUsernameWasTried() {
            failTimes(ip("203.0.113.8"), 20);
            // 该来源已锁定；换一个从未失败过的用户名也查不出"未锁定"——判定只看来源键
            assertThat(service.getRemainingLockMs(ip("203.0.113.8"))).isGreaterThan(0);
        }

        @Test
        @DisplayName("锁定期内继续失败不延长（同一截止时间）")
        void failuresDuringLockDoNotExtend() {
            failTimes(ip("203.0.113.9"), 21);
            long remaining = service.getRemainingLockMs(ip("203.0.113.9"));
            assertThat(remaining).isGreaterThan(0);
            service.recordFailure(ip("203.0.113.9"));
            assertThat(service.getRemainingLockMs(ip("203.0.113.9"))).isLessThanOrEqualTo(remaining);
        }

        @Test
        @DisplayName("登录成功清除来源计数，后续失败从头计数")
        void successClearsIpCount() {
            failTimes(ip("203.0.113.10"), 19);
            service.recordSuccess(ip("203.0.113.10"));
            assertThat(service.recordFailure(ip("203.0.113.10"))).isZero();
            assertThat(service.getRemainingLockMs(ip("203.0.113.10"))).isZero();
        }
    }

    @Nested
    @DisplayName("账号维度：跨来源的慢速撞库兜底")
    class UsernameDimension {

        @Test
        @DisplayName("默认阈值已放宽到 15 次/5 分钟（v2.44 前是 5 次/15 分钟，可被匿名锁人）")
        void defaultThresholdIsFifteenAndLocksFiveMinutes() {
            assertThat(failTimes(user("alice"), 14)).isZero();
            long lockMs = service.recordFailure(user("alice"));
            assertThat(lockMs).isEqualTo(5 * 60_000L);
        }

        @Test
        @DisplayName("阈值可配：app.security.login-lock.username-failures 生效，非正数回落默认")
        void thresholdIsConfigurable() throws Exception {
            LoginAttemptService custom = new LoginAttemptService();
            setUsernameFailures(custom, 3);
            assertThat(custom.recordFailure(user("bob"))).isZero();
            assertThat(custom.recordFailure(user("bob"))).isZero();
            assertThat(custom.recordFailure(user("bob"))).isEqualTo(5 * 60_000L);

            LoginAttemptService invalid = new LoginAttemptService();
            setUsernameFailures(invalid, 0);
            assertThat(failTimes(invalid, user("carol"), 14)).isZero();
            assertThat(invalid.recordFailure(user("carol"))).isEqualTo(5 * 60_000L);
        }

        private void setUsernameFailures(LoginAttemptService target, int value) throws Exception {
            Field field = LoginAttemptService.class.getDeclaredField("usernameFailures");
            field.setAccessible(true);
            field.setInt(target, value);
        }
    }

    @Nested
    @DisplayName("维度隔离与空值")
    class DimensionIsolation {

        @Test
        @DisplayName("用户名叫 alice 与来源是 alice 各算各的，不互相顶掉计数")
        void sameRawValueDifferentDimensionsDoNotCollide() {
            failTimes(user("alice"), 15);
            assertThat(service.getRemainingLockMs(user("alice"))).isGreaterThan(0);
            // 来源维度键独立：账号维度已锁不等于来源已锁
            assertThat(service.getRemainingLockMs(ip("alice"))).isZero();
            assertThat(failTimes(ip("alice"), 19)).isZero();
        }

        @Test
        @DisplayName("recordSuccess 只清传入的目标，另一个维度继续背着账")
        void successClearsOnlyGivenTargets() {
            failTimes(user("dave"), 14);
            failTimes(ip("203.0.113.21"), 19);
            service.recordSuccess(user("dave"));
            assertThat(service.recordFailure(user("dave"))).isZero();
            assertThat(service.getRemainingLockMs(ip("203.0.113.21"))).isZero();
            assertThat(service.recordFailure(ip("203.0.113.21"))).isGreaterThan(0);
        }

        @Test
        @DisplayName("空值目标一律忽略，不产生共享空桶")
        void blankTargetsIgnored() {
            assertThat(service.recordFailure(user(""))).isZero();
            assertThat(service.recordFailure(user(null))).isZero();
            assertThat(service.recordFailure(ip("   "))).isZero();
            assertThat(service.recordFailure(null)).isZero();
            assertThat(service.getRemainingLockMs(user(null))).isZero();
            service.recordSuccess((LoginLockTarget[]) null);
            service.recordSuccess(user(null), ip(""));
        }
    }
}
