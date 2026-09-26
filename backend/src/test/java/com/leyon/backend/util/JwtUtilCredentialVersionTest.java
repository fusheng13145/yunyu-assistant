package com.leyon.backend.util;

import com.leyon.backend.service.AccountCredentialService;
import com.leyon.backend.service.TokenBlacklistService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 账号凭据版本戳（v2.42 · C-93）
 * <p>
 * 要锁的性质：**账号侧一作废，手上那张签名仍然正确的令牌立刻不能用**。
 * 改密与注销此前都不触碰令牌，access 默认又有数十小时，所以"改密踢下线"只存在于直觉里。
 * 版本戳放在 {@link JwtUtil} 的两个校验入口而非各调用点，是因为全仓有 7 处校验入口
 * （HTTP 拦截器、WS 握手拦截器、文本 WS、语音信令，以及 /api/auth/** 的刷新、登出、自解析），逐处补必漏。
 * <p>
 * 与 {@code JwtUtilAccessTokenTest} 同理：用真实 JwtUtil，令牌要过 jjwt 签名与 claim
 * 编解码才有证明力；只有账号侧用桩。
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JwtUtilCredentialVersionTest {

    /** 测试专用密钥（非任何环境真实凭据），≥32 字节以通过启动期强度校验 */
    private static final String TEST_SECRET = "unit-test-only-secret-key-32-bytes-or-more!!";

    @Mock
    private TokenBlacklistService blacklistService;

    @Mock
    private AccountCredentialService credentialService;

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(blacklistService, credentialService);
        ReflectionTestUtils.setField(jwtUtil, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expiration", 900_000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshExpiration", 604_800_000L);
        jwtUtil.validateSecret();
        when(blacklistService.isBlacklisted(any())).thenReturn(false);
    }

    /** 用同一密钥手工签一枚令牌，用来构造"上线前签发、没有版本戳"的存量令牌 */
    private String legacyTokenWithoutVersion(long expiresInSeconds) {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        return Jwts.builder()
                .subject("u-1")
                .claim("username", "tester")
                .claim("type", JwtUtil.TOKEN_TYPE_ACCESS)
                .id("legacy-jti")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiresInSeconds * 1000L))
                .signWith(key)
                .compact();
    }

    /** 用同一密钥自行解出 claim：版本戳的读法不依赖生产代码里的诊断入口 */
    private Integer claimOf(String token, String name) {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        Number value = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload().get(name, Number.class);
        return value == null ? null : value.intValue();
    }

    @Test
    @DisplayName("签发的 access 与 refresh 令牌都带上账号当前凭据版本")
    void issuedTokensCarryCredentialVersion() {
        when(credentialService.currentVersion("u-1")).thenReturn(3);

        // 断言字面量 "tv"：claim 名一旦被改，存量令牌的版本判定会静默退化成"全部按 0 处理"
        assertThat(claimOf(jwtUtil.generateToken("u-1", "tester"), "tv")).isEqualTo(3);
        assertThat(claimOf(jwtUtil.generateRefreshToken("u-1", "tester"), "tv")).isEqualTo(3);
    }

    @Test
    @DisplayName("版本落后（已改密或已注销）的 access 令牌不再算会话凭据")
    void staleVersionAccessTokenIsRejected() {
        when(credentialService.currentVersion("u-1")).thenReturn(1);
        String access = jwtUtil.generateToken("u-1", "tester");

        // 签发当时有效
        when(credentialService.isCredentialLive(eq("u-1"), anyInt())).thenReturn(true);
        assertThat(jwtUtil.validateAccessToken(access)).isTrue();

        // 账号侧 bump 之后同一枚令牌立刻失效——不等它自然过期
        when(credentialService.isCredentialLive(eq("u-1"), eq(1))).thenReturn(false);
        assertThat(jwtUtil.validateAccessToken(access)).isFalse();
    }

    @Test
    @DisplayName("版本落后的 refresh 令牌不能续期：被盗令牌无法靠轮换无限存活")
    void staleVersionRefreshTokenCannotRenew() {
        when(credentialService.currentVersion("u-1")).thenReturn(1);
        String refresh = jwtUtil.generateRefreshToken("u-1", "tester");

        when(credentialService.isCredentialLive(eq("u-1"), eq(1))).thenReturn(false);
        assertThat(jwtUtil.validateToken(refresh)).isFalse();
        // 类型判定与"能不能续期"是两件事：类型仍要能识别，否则拒绝原因从"已作废"退化成"格式错"
        assertThat(jwtUtil.isTokenType(refresh, JwtUtil.TOKEN_TYPE_REFRESH)).isTrue();
    }

    @Test
    @DisplayName("存量无版本令牌按 0 处理：升级不踢人，但改密后同样失效")
    void legacyTokenWithoutVersionIsTreatedAsZero() {
        String legacy = legacyTokenWithoutVersion(3600L);
        assertThat(claimOf(legacy, "tv")).isNull();

        // 版本 0 的账号：存量令牌继续可用
        when(credentialService.isCredentialLive(eq("u-1"), eq(0))).thenReturn(true);
        assertThat(jwtUtil.validateAccessToken(legacy)).isTrue();

        // 该账号改过密码（版本已 bump 到 ≥1）：无版本戳的旧令牌一并失效
        when(credentialService.isCredentialLive(eq("u-1"), eq(0))).thenReturn(false);
        assertThat(jwtUtil.validateAccessToken(legacy)).isFalse();
    }

    @Test
    @DisplayName("账号行不存在时一律拒绝（含版本戳缺失的存量令牌）")
    void missingAccountIsRejectedRegardlessOfVersion() {
        when(credentialService.currentVersion("u-gone")).thenReturn(0);
        String access = jwtUtil.generateToken("u-gone", "ghost");
        // 注销目前全仓没有入口（C-95 只登记），此例锁的是校验口径本身：
        // "查不到账号"必须是 false，而不是被当成版本 0 放行
        when(credentialService.isCredentialLive(anyString(), anyInt())).thenReturn(false);

        assertThat(jwtUtil.validateAccessToken(access)).isFalse();
        assertThat(jwtUtil.validateToken(access)).isFalse();
    }

    @Test
    @DisplayName("校验入口不接受 refresh 令牌冒充 access（类型闸门不随版本戳回归）")
    void refreshTokenStillCannotPassAsAccess() {
        when(credentialService.currentVersion("u-1")).thenReturn(0);
        when(credentialService.isCredentialLive(any(), anyInt())).thenReturn(true);
        String refresh = jwtUtil.generateRefreshToken("u-1", "tester");

        assertThat(jwtUtil.validateAccessToken(refresh)).isFalse();
        assertThat(jwtUtil.validateToken(refresh)).isTrue();
    }
}
