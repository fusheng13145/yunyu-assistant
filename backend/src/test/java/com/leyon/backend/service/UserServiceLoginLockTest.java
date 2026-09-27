package com.leyon.backend.service;

import com.leyon.backend.entity.User;
import com.leyon.backend.mapper.UserMapper;
import com.leyon.backend.service.LoginAttemptService.LoginLockTarget;
import com.leyon.backend.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 登录锁定集成测试（真实 LoginAttemptService + UserService，v2.44 双维度）
 * <p>
 * 这里锁的是**攻击者视角的代价形状**，阈值数字本身在 {@link LoginAttemptServiceTest}：
 * ①轮换用户名绕不开来源自己的额度；②来源锁定不连坐别的来源；③账号维度只剩兜底作用，
 * 且不再有"5 次就锁 15 分钟"这种匿名锁人的性价比；④成功登录把两个维度的旧账一起清掉。
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceLoginLockTest {

    private static final String PASSWORD = "correct123";
    private static final String ATTACKER_IP = "203.0.113.7";
    private static final String OTHER_IP = "198.51.100.9";

    @Mock
    private UserMapper userMapper;

    @Mock
    private InviteCodeService inviteCodeService;

    private LoginAttemptService attemptService;
    private UserService userService;

    @BeforeEach
    void setUp() throws Exception {
        attemptService = new LoginAttemptService();
        JwtUtil jwtUtil = new JwtUtil(new TokenBlacklistService(), com.leyon.backend.support.JwtTestSupport.alwaysLiveCredentials());
        setField(jwtUtil, "secret", "test-secret-key-0123456789abcdef0123456789abcdef");
        setField(jwtUtil, "expiration", 86400000L);
        setField(jwtUtil, "refreshExpiration", 604800000L);

        User user = new User();
        user.setId("u1");
        user.setUsername("alice");
        user.setPassword(new BCryptPasswordEncoder().encode(PASSWORD));

        userService = new UserService(userMapper, jwtUtil, attemptService, inviteCodeService);
        when(userMapper.selectOne(any())).thenReturn(user);
    }

    private Map<String, String> login(String username, String password, String clientIp) {
        return userService.login(username, password, clientIp);
    }

    /** 连打 n 次错误口令，每次换一个用户名（撞库者的自然行为：目标不唯一） */
    private void failTimesFrom(String clientIp, int times) {
        for (int i = 0; i < times; i++) {
            String username = "victim" + i;
            assertThatThrownBy(() -> login(username, "wrongpass", clientIp))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("用户名或密码错误");
        }
    }

    /** 每次换一个来源地址打错误口令（绕过来源额度、专打账号兜底阈值的行为形状） */
    private void failAccountFromDistinctIps(String username, int times, int firstOctetTail) {
        for (int i = 0; i < times; i++) {
            String clientIp = "203.0.113." + (firstOctetTail + i);
            assertThatThrownBy(() -> login(username, "wrongpass", clientIp))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("用户名或密码错误");
        }
    }

    @Test
    @DisplayName("来源维度为主：同一来源打满 20 次后即便口令正确也被拒，轮换用户名绕不开自己的额度")
    void rotatingUsernamesCannotEvadeIpLock() {
        failTimesFrom(ATTACKER_IP, 20);
        assertThatThrownBy(() -> login("alice", PASSWORD, ATTACKER_IP))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("锁定")
                .hasMessageContaining("分钟后再试");
    }

    @Test
    @DisplayName("来源锁定不连坐：另一来源的同一账号照常登录；同一来源未打满额度也照常登录")
    void ipLockIsScopedToThatSourceOnly() {
        failTimesFrom(ATTACKER_IP, 20);
        assertThat(login("alice", PASSWORD, OTHER_IP)).containsEntry("username", "alice");

        failTimesFrom("203.0.113.33", 14);
        assertThat(login("alice", PASSWORD, "203.0.113.33")).containsEntry("username", "alice");
    }

    @Test
    @DisplayName("账号维度只做兜底：跨来源累计 14 次不锁，第 15 次才锁且锁该账号的所有来源")
    void accountDimensionLocksAtFifteenAcrossSources() {
        failAccountFromDistinctIps("alice", 14, 20);
        assertThat(attemptService.getRemainingLockMs(LoginLockTarget.username("alice"))).isZero();
        assertThat(login("alice", PASSWORD, OTHER_IP)).containsEntry("username", "alice");

        failAccountFromDistinctIps("bob", 15, 40);
        assertThat(attemptService.getRemainingLockMs(LoginLockTarget.username("bob"))).isGreaterThan(0);
        // 账号兜底命中后影响该账号的所有来源
        assertThatThrownBy(() -> login("bob", PASSWORD, "198.51.100.77")).hasMessageContaining("锁定");
    }

    @Test
    @DisplayName("账号锁定不连坐别的账号：alice 锁了，另一账号还能登录")
    void accountLockDoesNotImplicateOtherAccounts() {
        failAccountFromDistinctIps("alice", 15, 20);
        assertThat(login("bob", PASSWORD, "198.51.100.77")).containsEntry("username", "alice");
    }

    @Test
    @DisplayName("客户端地址为空时仍按账号维度兜底，不因为取不到来源就放弃锁定")
    void nullIpStillUsesAccountBackstop() {
        for (int i = 0; i < 15; i++) {
            assertThatThrownBy(() -> login("alice", "wrongpass", null))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> login("alice", PASSWORD, null)).hasMessageContaining("锁定");
    }

    @Test
    @DisplayName("登录成功同时清除账号与来源两个维度的旧账")
    void successClearsBothDimensions() {
        failTimesFrom(ATTACKER_IP, 14);
        login("alice", PASSWORD, ATTACKER_IP);
        // 两个维度都从头计数：再来 14 次仍应是"口令错误"而不是"锁定"
        failTimesFrom(ATTACKER_IP, 14);
        assertThat(login("alice", PASSWORD, ATTACKER_IP)).containsEntry("username", "alice");
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
