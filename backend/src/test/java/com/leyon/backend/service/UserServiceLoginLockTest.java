package com.leyon.backend.service;

import com.leyon.backend.entity.User;
import com.leyon.backend.mapper.UserMapper;
import com.leyon.backend.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 登录锁定集成测试（真实 LoginAttemptService + UserService）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceLoginLockTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private InviteCodeService inviteCodeService;

    @Test
    void fifthFailureLocksAndSixthLoginRejected() throws Exception {
        LoginAttemptService attemptService = new LoginAttemptService();
        JwtUtil jwtUtil = new JwtUtil(new TokenBlacklistService());
        // 反射填充 JwtUtil 字段
        setField(jwtUtil, "secret", "test-secret-key-0123456789abcdef0123456789abcdef");
        setField(jwtUtil, "expiration", 86400000L);
        setField(jwtUtil, "refreshExpiration", 604800000L);

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        User user = new User();
        user.setId("u1");
        user.setUsername("alice");
        user.setPassword(encoder.encode("correct123"));

        UserService userService = new UserService(userMapper, jwtUtil, attemptService, inviteCodeService);
        when(userMapper.selectOne(any())).thenReturn(user);

        // 5 次错误密码
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> userService.login("alice", "wrongpass"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("用户名或密码错误");
        }
        // 锁定生效
        assertThat(attemptService.getRemainingLockMs("alice")).isGreaterThan(0);
        // 第 6 次即使密码正确也拒绝
        assertThatThrownBy(() -> userService.login("alice", "correct123"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("锁定");
    }

    @Test
    void successfulLoginClearsFailures() throws Exception {
        LoginAttemptService attemptService = new LoginAttemptService();
        JwtUtil jwtUtil = new JwtUtil(new TokenBlacklistService());
        setField(jwtUtil, "secret", "test-secret-key-0123456789abcdef0123456789abcdef");
        setField(jwtUtil, "expiration", 86400000L);
        setField(jwtUtil, "refreshExpiration", 604800000L);

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        User user = new User();
        user.setId("u1");
        user.setUsername("alice");
        user.setPassword(encoder.encode("correct123"));

        UserService userService = new UserService(userMapper, jwtUtil, attemptService, inviteCodeService);
        when(userMapper.selectOne(any())).thenReturn(user);

        // 4 次失败
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> userService.login("alice", "wrongpass"))
                    .isInstanceOf(RuntimeException.class);
        }
        // 成功登录清除计数
        userService.login("alice", "correct123");
        assertThat(attemptService.getRemainingLockMs("alice")).isZero();
        // 后续失败从头计数，不立即锁定
        assertThatThrownBy(() -> userService.login("alice", "wrongpass"))
                .isInstanceOf(RuntimeException.class);
        assertThat(attemptService.getRemainingLockMs("alice")).isZero();
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}