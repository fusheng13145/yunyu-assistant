package com.leyon.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModelBaseUrlGuard 单测（v2.62 · C-128 · 候选 ㊼）
 * <p>
 * Spring AI 1.0.0 的 {@code spring.ai.openai.chat.completions-path} 默认值自带 {@code /v1}
 * （实测 spring-configuration-metadata.json 的 defaultValue 即 {@code /v1/chat/completions}），
 * 所以 base-url 里再写 {@code /v1} 会合成出 {@code /v1/v1/chat/completions}。
 * 本测试锁的是"误配在启动时就被点名"，而不是"报错出现在第一次对话里"。
 *
 * @author leyon
 */
class ModelBaseUrlGuardTest {

    @Test
    @DisplayName("base-url 末尾带 /v1 ⇒ 点名合成后的双 /v1 出站地址与要改的变量")
    void v1SuffixIsFlagged() {
        String warning = ModelBaseUrlGuard.check("https://platform.deepseek.com/v1", null);

        assertNotNull(warning);
        assertTrue(warning.contains("/v1/v1/chat/completions"), "应给出合成后的真实出站路径: " + warning);
        assertTrue(warning.contains("OPENAI_BASE_URL"), "应指明要改哪个变量: " + warning);
    }

    @Test
    @DisplayName("尾斜杠形态同等判定（多一个斜杠不是另一种错）")
    void v1SuffixWithTrailingSlashIsFlagged() {
        assertNotNull(ModelBaseUrlGuard.check("https://platform.deepseek.com/v1/", "/v1/chat/completions"));
    }

    @Test
    @DisplayName("主机根合规：默认 completions-path 自带 /v1，无需在 base-url 里重复")
    void hostRootIsAccepted() {
        assertNull(ModelBaseUrlGuard.check("https://platform.deepseek.com", null));
        assertNull(ModelBaseUrlGuard.check("https://api.openai.com/", "/v1/chat/completions"));
    }

    @Test
    @DisplayName("网关前缀不含 /v1 时合规（自建代理在主机名后挂一段路径是合法用法）")
    void gatewayPathPrefixIsAccepted() {
        assertNull(ModelBaseUrlGuard.check("https://gw.example.com/proxy/ai", null));
    }

    @Test
    @DisplayName("显式把 completions-path 改短时不告警（两条修法都判得住）")
    void shortenedCompletionsPathIsAccepted() {
        assertNull(ModelBaseUrlGuard.check("https://platform.deepseek.com/v1", "/chat/completions"));
    }

    @Test
    @DisplayName("缺失或空白的 base-url 不判定（交由上游默认值）")
    void absentBaseUrlIsNotJudged() {
        assertNull(ModelBaseUrlGuard.check(null, null));
        assertNull(ModelBaseUrlGuard.check("   ", null));
    }

    @Test
    @DisplayName("validate() 读真实配置键，误配时只告警不阻断启动")
    void validateWarnsInsteadOfThrowing() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.ai.openai.base-url", "https://platform.deepseek.com/v1");

        // 与另外两个守卫的分工：占位凭据/全网卡 actuator 是必须拒绝启动的，
        // base-url 误配只影响对话一条链路，阻断启动会把一个配置笔误放大成整站不可用
        assertDoesNotThrow(new ModelBaseUrlGuard(env)::validate);
    }
}
