package com.leyon.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JWT 两级令牌有效期的配置契约（v2.42 · C-94）
 * <p>
 * 锁三条"改了默认值就会静默伤人"的性质：
 * ① access 不能长回数十小时——它决定被盗令牌在版本戳之外还能用多久；
 * ② access 必须明显长于前端续期阈值，否则静默续期来不及介入、正常用户被踢；
 * ③ access 必须短于 refresh，否则"过期后续期"这条通道在时间上不存在。
 * <p>
 * 读的是 classpath 上真正会被加载的那份 application.yaml，不是复制品。
 *
 * @author leyon
 */
class JwtLifetimeTest {

    /** 前端 authFetch 的临期阈值（毫秒），见 frontend/src/api/auth.ts 的 RENEW_SKEW_MS */
    private static final long FRONTEND_RENEW_SKEW_MS = 60_000L;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jwtConfig() {
        try (InputStream in = JwtLifetimeTest.class.getResourceAsStream("/application.yaml")) {
            assertThat(in).as("application.yaml 必须在测试类路径上").isNotNull();
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> app = (Map<String, Object>) root.get("app");
            return (Map<String, Object>) ((Map<String, Object>) app.get("jwt"));
        } catch (Exception e) {
            throw new AssertionError("读取 application.yaml 失败", e);
        }
    }

    /** 取 {@code ${VAR:default}} 形态里的默认值 */
    private static long defaultOf(String placeholder) {
        String value = placeholder.trim();
        assertThat(value).as("有效期必须保持 ${VAR:default} 形态").matches("\\$\\{[A-Z0-9_]+:[^}]+\\}");
        int colon = value.lastIndexOf(':');
        return Long.parseLong(value.substring(colon + 1, value.length() - 1).trim());
    }

    @Test
    @DisplayName("access 与 refresh 的默认有效期符合短 access / 长 refresh 的分工")
    void defaultsKeepTheTwoTierContract() {
        Map<String, Object> jwt = jwtConfig();
        long access = defaultOf((String) jwt.get("expiration"));
        long refresh = defaultOf((String) jwt.get("refresh-expiration"));

        assertThat(access).as("access 默认有效期").isLessThanOrEqualTo(900_000L);
        assertThat(access).as("必须给前端静默续期留出窗口").isGreaterThan(FRONTEND_RENEW_SKEW_MS * 5);
        assertThat(access).as("access 长于 refresh 时续期通道不存在").isLessThan(refresh);
    }

    @Test
    @DisplayName("改默认值必须同时改占位符变量名（防止绕过 .env 硬写数值）")
    void valuesStayOverridableByEnvironment() {
        Map<String, Object> jwt = jwtConfig();
        assertThat((String) jwt.get("expiration")).startsWith("${JWT_EXPIRATION:");
        assertThat((String) jwt.get("refresh-expiration")).startsWith("${JWT_REFRESH_EXPIRATION:");
    }
}
