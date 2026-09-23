package com.leyon.backend.util;

import com.leyon.backend.service.TokenBlacklistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * JwtUtil 会话凭据校验单元测试（v2.32 全链路验证产出）
 * 覆盖：access/refresh 双令牌的真实签发-回验、validateAccessToken 的类型闸门、黑名单命中、过期令牌
 * <p>
 * 这里刻意用真实 JwtUtil（只 ReflectionTestUtils 注入配置）而不是 mock 它：
 * 类型闸门要经过 jjwt 的签名与 claim 编解码才有证明力，mock 只能证明"调了对应方法"。
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JwtUtilAccessTokenTest {

    /** 测试专用密钥（非任何环境真实凭据），≥32 字节以通过启动期强度校验 */
    private static final String TEST_SECRET = "unit-test-only-secret-key-32-bytes-or-more!!";

    @Mock
    private TokenBlacklistService blacklistService;

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(blacklistService);
        ReflectionTestUtils.setField(jwtUtil, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expiration", 3_600_000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshExpiration", 604_800_000L);
        jwtUtil.validateSecret();
        when(blacklistService.isBlacklisted(any())).thenReturn(false);
    }

    @Test
    void accessTokenIsAcceptedAsSessionCredential() {
        String access = jwtUtil.generateToken("u-1", "tester");
        assertThat(jwtUtil.validateToken(access)).isTrue();
        assertThat(jwtUtil.validateAccessToken(access)).isTrue();
        assertThat(jwtUtil.getUserIdFromToken(access)).isEqualTo("u-1");
    }

    @Test
    void refreshTokenIsRejectedAsSessionCredential() {
        String refresh = jwtUtil.generateRefreshToken("u-1", "tester");
        // 刷新令牌本身当然有效（否则无法续期）……
        assertThat(jwtUtil.validateToken(refresh)).isTrue();
        assertThat(jwtUtil.isTokenType(refresh, JwtUtil.TOKEN_TYPE_REFRESH)).isTrue();
        // ……但不能当 Bearer 用作会话凭据
        assertThat(jwtUtil.validateAccessToken(refresh)).isFalse();
    }

    @Test
    void blacklistedAccessTokenIsRejectedByBothChecks() {
        String access = jwtUtil.generateToken("u-1", "tester");
        String jti = jwtUtil.getJtiFromToken(access);
        assertThat(jti).isNotBlank();
        doAnswer(invocation -> jti.equals(invocation.getArgument(0)))
                .when(blacklistService).isBlacklisted(anyString());
        assertThat(jwtUtil.validateToken(access)).isFalse();
        assertThat(jwtUtil.validateAccessToken(access)).isFalse();
    }

    @Test
    void expiredAccessTokenIsRejected() {
        ReflectionTestUtils.setField(jwtUtil, "expiration", -1_000L);
        String expired = jwtUtil.generateToken("u-1", "tester");
        assertThat(jwtUtil.validateAccessToken(expired)).isFalse();
    }

    @Test
    void foreignSignatureAndMalformedInputAreRejected() {
        ReflectionTestUtils.setField(jwtUtil, "secret", "another-unit-test-secret-value-32-bytes-ok!");
        String foreign = jwtUtil.generateToken("u-1", "tester");
        ReflectionTestUtils.setField(jwtUtil, "secret", TEST_SECRET);
        assertThat(jwtUtil.validateAccessToken(foreign)).isFalse();
        assertThat(jwtUtil.validateAccessToken("not-a-jwt")).isFalse();
        assertThat(jwtUtil.validateAccessToken("")).isFalse();
        //  alg:none 的"免签名"令牌同样不被接受（Jwts.parser 要求签名密钥）
        assertThat(jwtUtil.validateAccessToken("eyJhbGciOiJub25lIn0.eyJzdWIiOiJ1LTEifQ.")).isFalse();
    }
}
