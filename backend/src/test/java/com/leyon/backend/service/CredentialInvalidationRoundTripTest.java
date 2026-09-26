package com.leyon.backend.service;

import com.leyon.backend.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 改密即作废的链式验收（v2.42 · C-93）
 * <p>
 * 单测各自只证明一环：签发写了 tv claim、判据比对了版本、changePassword 推进了版本。
 * 这里把真实 {@code JwtUtil} + 真实 {@code AccountCredentialService} + 真实
 * {@code UserService.changePassword} 串起来跑一遍，因为用户感知的性质是"改完密，旧设备立刻用不了"，
 * 而不是"某个方法返回了 true"。任何一环被改动（claim 名、签发时机、版本推进、判据位置）都会在这里变红。
 * <p>
 * 令牌由 {@code jwtUtil} 直接签发，不走 {@code UserService.login}：那两步（{@code generateToken} /
 * {@code generateRefreshToken}）就是登录尾部做的事，而 login 前置的用户名查询依赖 MyBatis-Plus
 * 的 lambda 缓存（要 Spring 容器先初始化过 TableInfo），放进单测会变成"看测试顺序脸色"的假绿。
 * 真机一轮（手册 7.4 v2.42）覆盖从 login 到 401 的完整链路。
 *
 * @author leyon
 */
class CredentialInvalidationRoundTripTest {

    private static final String TEST_SECRET = "round-trip-only-secret-key-32-bytes-or-more!";
    private static final String USER_ID = "u-1";

    private FakeUserMapper userMapper;
    private JwtUtil jwtUtil;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userMapper = new FakeUserMapper().withPassword(USER_ID,
                new BCryptPasswordEncoder().encode("old-pass"), 0);
        jwtUtil = new JwtUtil(new TokenBlacklistService(), new AccountCredentialService(userMapper));
        ReflectionTestUtils.setField(jwtUtil, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expiration", 900_000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshExpiration", 604_800_000L);
        jwtUtil.validateSecret();

        userService = new UserService(userMapper, jwtUtil, new LoginAttemptService(),
                Mockito.mock(InviteCodeService.class));
    }

    /** 登录尾部真正执行的两次签发 */
    private String[] issueSession() {
        return new String[]{
                jwtUtil.generateToken(USER_ID, "tester"),
                jwtUtil.generateRefreshToken(USER_ID, "tester")
        };
    }

    @Test
    @DisplayName("改密前签发的 access 与 refresh 都可用（链式基线，防止把'一直拒绝'当成通过）")
    void issuedTokensAreUsableBeforeChange() {
        String[] session = issueSession();

        assertThat(jwtUtil.validateAccessToken(session[0])).isTrue();
        assertThat(jwtUtil.validateToken(session[1])).isTrue();
    }

    @Test
    @DisplayName("改密成功的那一刻，此前签发的 access 与 refresh 同时失效——含发起改密的这台设备")
    void passwordChangeInvalidatesEveryIssuedToken() {
        String[] session = issueSession();

        assertThat(userService.changePassword(USER_ID, "old-pass", "new-pass")).isTrue();

        assertThat(jwtUtil.validateAccessToken(session[0])).isFalse();
        assertThat(jwtUtil.validateToken(session[0])).isFalse();
        // 刷新令牌走同一个版本判据：/api/auth/refresh 只调 validateToken、不重验密码，
        // 少了这一环，被盗的 refresh 仍能换出新 access，"改密踢下线"只是半条防线
        assertThat(jwtUtil.validateToken(session[1])).isFalse();
        assertThat(jwtUtil.validateAccessToken(session[1])).isFalse();
    }

    @Test
    @DisplayName("原密码不对时不改版本，正在使用的令牌不会被一次误操作踢掉")
    void wrongOldPasswordKeepsTokensAlive() {
        String[] session = issueSession();

        assertThat(userService.changePassword(USER_ID, "wrong-pass", "new-pass")).isFalse();
        assertThat(userMapper.versionOf(USER_ID)).isZero();

        assertThat(jwtUtil.validateAccessToken(session[0])).isTrue();
    }

    @Test
    @DisplayName("作废只针对旧凭据：改密后重新签发的令牌立即可用，账号没有锁死")
    void reIssueAfterChangeYieldsLiveTokens() {
        String[] stale = issueSession();
        assertThat(userService.changePassword(USER_ID, "old-pass", "new-pass")).isTrue();
        assertThat(jwtUtil.validateAccessToken(stale[0])).isFalse();

        String[] fresh = issueSession();

        assertThat(jwtUtil.validateAccessToken(fresh[0])).isTrue();
        assertThat(jwtUtil.validateToken(fresh[1])).isTrue();
        // 新令牌生效不能反过来把旧令牌又放行
        assertThat(jwtUtil.validateAccessToken(stale[0])).isFalse();
    }

    @Test
    @DisplayName("多设备登录：任一设备改密，其余设备的令牌一起失效")
    void everySessionDiesTogether() {
        String laptop = jwtUtil.generateToken(USER_ID, "tester");
        String phone = jwtUtil.generateToken(USER_ID, "tester");
        assertThat(jwtUtil.validateAccessToken(laptop)).isTrue();
        assertThat(jwtUtil.validateAccessToken(phone)).isTrue();

        assertThat(userService.changePassword(USER_ID, "old-pass", "new-pass")).isTrue();

        assertThat(jwtUtil.validateAccessToken(laptop)).isFalse();
        assertThat(jwtUtil.validateAccessToken(phone)).isFalse();
    }
}
