package com.leyon.backend.service;

import com.leyon.backend.util.JwtUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 令牌黑名单单元测试
 * 覆盖：加入黑名单后校验失效、jti 独立影响、空白 jti 安全处理
 *
 * @author leyon
 */
class TokenBlacklistServiceTest {

    private final TokenBlacklistService service = new TokenBlacklistService();

    @Test
    void blacklist_afterAddTokenInvalidated() {
        String jti = "jti-1";
        assertThat(service.isBlacklisted(jti)).isFalse();
        service.blacklist(jti, 60_000L);
        assertThat(service.isBlacklisted(jti)).isTrue();
    }

    @Test
    void blacklist_emptyJtiIgnored() {
        service.blacklist(null, 60_000L);
        service.blacklist("", 60_000L);
        service.blacklist("   ", 60_000L);
        assertThat(service.isBlacklisted(null)).isFalse();
        assertThat(service.isBlacklisted("")).isFalse();
    }

    @Test
    void blacklist_expiredEntryAutoCleared() throws InterruptedException {
        String jti = "jti-expire";
        // 黑名单最短生效时长为 1000ms，等待超过后应自动清除
        service.blacklist(jti, 1000L);
        Thread.sleep(1200L);
        // 过期后视为有效（条目自动清除）
        assertThat(service.isBlacklisted(jti)).isFalse();
    }

    @Test
    void blacklist_isolatedByJti() {
        service.blacklist("jti-a", 60_000L);
        assertThat(service.isBlacklisted("jti-a")).isTrue();
        assertThat(service.isBlacklisted("jti-b")).isFalse();
    }

    @Test
    void integrationWithJwtUtil_jtiIsUniquePerToken() throws Exception {
        TokenBlacklistService bl = new TokenBlacklistService();
        JwtUtil jwtUtil = new JwtUtil(bl);
        // 纯单测环境绕过 @Value 注入，反射填充密钥与有效期
        java.lang.reflect.Field secretField = JwtUtil.class.getDeclaredField("secret");
        secretField.setAccessible(true);
        secretField.set(jwtUtil, "test-secret-key-0123456789abcdef0123456789abcdef");
        java.lang.reflect.Field expField = JwtUtil.class.getDeclaredField("expiration");
        expField.setAccessible(true);
        expField.set(jwtUtil, 86400000L);
        java.lang.reflect.Field refreshField = JwtUtil.class.getDeclaredField("refreshExpiration");
        refreshField.setAccessible(true);
        refreshField.set(jwtUtil, 604800000L);

        String t1 = jwtUtil.generateToken("u1", "alice");
        String t2 = jwtUtil.generateToken("u1", "alice");
        String jti1 = jwtUtil.getJtiFromToken(t1);
        String jti2 = jwtUtil.getJtiFromToken(t2);
        assertThat(jti1).isNotEqualTo(jti2);
        assertThat(jti1).isNotNull();

        // 拉黑 t1 后 t1 失效、t2 仍有效
        bl.blacklist(jti1, 60_000L);
        assertThat(jwtUtil.validateToken(t1)).isFalse();
        assertThat(jwtUtil.validateToken(t2)).isTrue();
    }
}