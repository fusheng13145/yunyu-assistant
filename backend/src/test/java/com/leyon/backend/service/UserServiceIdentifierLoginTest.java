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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 三态标识登录路由测试（v2.89）
 * <p>
 * 锁的是"一个输入框三种标识"最容易被改坏的四处：
 * <ul>
 *   <li><b>路由前必须归一化</b>——把原始输入直接当查询条件，" Alice@Example.COM " 就查不到人；</li>
 *   <li><b>形状判据不能锁死存量</b>——历史纯数字用户名在形状上是手机号，形状查不到时必须回落用户名，
 *       否则升级即把老账号关在门外（本文件第 4 例是这条的唯一防线，删掉它无人发现）；</li>
 *   <li><b>多命中要拒绝而不是挑一行</b>——{@code LIMIT 1} 会把"库里出现两行同邮箱"变成"随机一人能登录"，
 *       而口令校验对挑中的那行仍然会通过，看上去一切正常；</li>
 *   <li><b>账号维度锁定按归一化标识记键</b>——按原始串记键时，交替大小写就能绕开 15 次阈值。</li>
 * </ul>
 * 与 {@link UserServiceLoginLockTest} 的分工：那边锁阈值形状，这边锁"标识落到哪一列、以什么形式落"。
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceIdentifierLoginTest {

    private static final String PASSWORD = "correct123";
    private static final String IP = "203.0.113.7";

    @Mock
    private UserMapper userMapper;

    @Mock
    private InviteCodeService inviteCodeService;

    private LoginAttemptService attemptService;
    private UserService userService;

    @BeforeEach
    void setUp() throws Exception {
        attemptService = new LoginAttemptService();
        JwtUtil jwtUtil = new JwtUtil(new TokenBlacklistService(),
                com.leyon.backend.support.JwtTestSupport.alwaysLiveCredentials());
        setField(jwtUtil, "secret", "test-secret-key-0123456789abcdef0123456789abcdef");
        setField(jwtUtil, "expiration", 86400000L);
        setField(jwtUtil, "refreshExpiration", 604800000L);
        userService = new UserService(userMapper, jwtUtil, attemptService, inviteCodeService,
                new IdentifierPolicy());
    }

    private User user(String id, String username, String email, String phone) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setPhone(phone);
        user.setPassword(new BCryptPasswordEncoder().encode(PASSWORD));
        return user;
    }

    private Map<String, String> login(String identifier, String password) {
        return userService.login(identifier, password, IP);
    }

    @Test
    @DisplayName("邮箱标识：按归一化后的邮箱查 email 列，命中即可登录")
    void emailIdentifierLooksUpNormalizedEmail() {
        when(userMapper.selectActiveByEmail("alice@example.com"))
                .thenReturn(List.of(user("u1", "alice", "alice@example.com", null)));

        assertThat(login("  Alice@Example.COM ", PASSWORD)).containsEntry("userId", "u1");
        verify(userMapper).selectActiveByEmail("alice@example.com");
    }

    @Test
    @DisplayName("手机号标识：剥离分隔符后查 phone 列，命中即可登录")
    void phoneIdentifierLooksUpNormalizedPhone() {
        when(userMapper.selectActiveByPhone("+8613800000000"))
                .thenReturn(List.of(user("u2", "bob", null, "+8613800000000")));

        assertThat(login("+86 (138) 0000-0000", PASSWORD)).containsEntry("userId", "u2");
        verify(userMapper).selectActiveByPhone("+8613800000000");
    }

    @Test
    @DisplayName("用户名标识：只查 username 列，不顺手去查邮箱与手机号")
    void usernameIdentifierOnlyLooksUpUsername() {
        when(userMapper.selectActiveByUsername("alice"))
                .thenReturn(List.of(user("u3", "alice", null, null)));

        assertThat(login("alice", PASSWORD)).containsEntry("userId", "u3");
        verify(userMapper, never()).selectActiveByEmail(anyString());
        verify(userMapper, never()).selectActiveByPhone(anyString());
    }

    @Test
    @DisplayName("历史纯数字用户名：形状像手机号但没填手机号，回落 username 列仍能登录")
    void legacyNumericUsernameFallsBackToUsernameColumn() {
        // 形状判据是读侧启发式，不是存量约束：库里叫 13800000000 的用户名不能因为这次升级登不进来
        when(userMapper.selectActiveByPhone("13800000000")).thenReturn(List.of());
        when(userMapper.selectActiveByUsername("13800000000"))
                .thenReturn(List.of(user("u4", "13800000000", null, null)));

        assertThat(login("13800000000", PASSWORD)).containsEntry("userId", "u4");
    }

    @Test
    @DisplayName("同邮箱命中多行：拒绝登录并点名多账号，绝不挑一行放行")
    void ambiguousEmailMatchFailsClosed() {
        when(userMapper.selectActiveByEmail("dup@example.com"))
                .thenReturn(List.of(user("u5", "first", "dup@example.com", null),
                        user("u6", "second", "dup@example.com", null)));

        assertThatThrownBy(() -> login("dup@example.com", PASSWORD))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("多个账号");

        verify(userMapper, never()).selectActiveByUsername(anyString());
    }

    @Test
    @DisplayName("标识不存在与口令错误返回同一文案，不给用户名枚举留缺口")
    void unknownIdentifierAndWrongPasswordShareOneWording() {
        when(userMapper.selectActiveByEmail("ghost@example.com")).thenReturn(List.of());
        when(userMapper.selectActiveByUsername("alice"))
                .thenReturn(List.of(user("u7", "alice", null, null)));

        String unknown = catchMessage(() -> login("ghost@example.com", PASSWORD));
        String badPassword = catchMessage(() -> login("alice", "wrong12345"));

        assertThat(unknown).isEqualTo("账号或密码错误").isEqualTo(badPassword);
    }

    @Test
    @DisplayName("账号维度锁定按归一化标识记键：交替大小写绕不开同一桶")
    void caseVariantsOfOneEmailShareTheAccountLockBucket() {
        when(userMapper.selectActiveByEmail("alice@example.com")).thenReturn(List.of());

        for (int i = 0; i < 15; i++) {
            String typed = (i % 2 == 0) ? "ALICE@Example.COM" : "alice@example.com";
            assertThatThrownBy(() -> login(typed, PASSWORD)).isInstanceOf(RuntimeException.class);
        }
        // 若按键记的是原始串，两串各 7~8 次都够不到 15 的阈值，这里就会是 0
        assertThat(attemptService.getRemainingLockMs(LoginLockTarget.username("alice@example.com")))
                .isGreaterThan(0L);
    }

    @Test
    @DisplayName("登录成功清掉的是归一化键：大小写变体不再背着旧账")
    void successClearsTheNormalizedAccountKey() {
        when(userMapper.selectActiveByEmail("alice@example.com"))
                .thenReturn(List.of(user("u8", "alice", "alice@example.com", null)));

        assertThatThrownBy(() -> login("ALICE@Example.COM", "wrong12345")).isInstanceOf(RuntimeException.class);
        login("alice@example.com", PASSWORD);

        assertThat(attemptService.getRemainingLockMs(LoginLockTarget.username("alice@example.com"))).isZero();
    }

    private String catchMessage(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            return e.getMessage();
        }
        throw new AssertionError("预期登录被拒，实际通过");
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
