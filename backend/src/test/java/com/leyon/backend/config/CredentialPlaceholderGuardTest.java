package com.leyon.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CredentialPlaceholderGuard 单测（v2.34）
 * <p>
 * 覆盖"照抄 .env.example 未填写就启动"的三类实况：占位字面量、空串、变量完全未注入。
 *
 * @author leyon
 */
class CredentialPlaceholderGuardTest {

    @Test
    @DisplayName("数据库账号仍是模板占位字面量 ⇒ 拒绝启动并点名变量与修法")
    void placeholderLiteralIsRejected() {
        String violation = CredentialPlaceholderGuard.check("[REQUIRED]", "real-pw", "real-key");

        assertNotNull(violation);
        assertTrue(violation.contains("DB_USER"), "应点名 DB_USER: " + violation);
        assertTrue(violation.contains(".env"), "应指向 .env 填写动作: " + violation);
    }

    @Test
    @DisplayName("数据库密码与模型 Key 的占位字面量同样被点名")
    void everyPlaceholderVariableIsNamed() {
        String violation = CredentialPlaceholderGuard.check("user", "[REQUIRED]", "ci-dummy-key");
        assertNotNull(violation);
        assertTrue(violation.contains("DB_PASSWORD"), violation);

        violation = CredentialPlaceholderGuard.check("user", "pw", "[REQUIRED]");
        assertNotNull(violation);
        assertTrue(violation.contains("OPENAI_API_KEY"), violation);
    }

    @Test
    @DisplayName("空串与缺失同等对待（.env 里写 DB_PASSWORD= 也算未填写）")
    void blankValueIsRejected() {
        assertNotNull(CredentialPlaceholderGuard.check("user", "   ", "key"));
    }

    @Test
    @DisplayName("CI 与本地冒烟的哑值（非占位字面量、非空）不拦截")
    void dummyValuesPassThrough() {
        assertNull(CredentialPlaceholderGuard.check("ci", "ci-placeholder-unused", "ci-dummy-key-not-a-real-key"));
    }

    @Test
    @DisplayName("null（变量完全未注入）⇒ 报可执行的人话而非占位符解析栈")
    void nullValueIsRejectedWithClearMessage() {
        String violation = CredentialPlaceholderGuard.check(null, "pw", "key");

        assertNotNull(violation);
        assertTrue(violation.contains("DB_USER"), violation);
    }

    @Test
    @DisplayName("yaml 的 ${DB_USER} 解析不到环境变量时，validate() 仍给出点名变量的违规说明")
    void unresolvedNestedPlaceholderIsReportedNicely() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.username", "${DB_USER}")
                .withProperty("spring.datasource.password", "${DB_PASSWORD:}")
                .withProperty("spring.ai.openai.api-key", "${OPENAI_API_KEY}");
        // DB_USER 无任何来源：严格解析会抛"Could not resolve placeholder"，守卫须把它翻译成人话

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                new CredentialPlaceholderGuard(env)::validate);
        assertTrue(ex.getMessage().contains("DB_USER"), ex.getMessage());
    }

    @Test
    @DisplayName("validate() 在违规时抛 IllegalStateException 阻断启动")
    void validateThrowsOnViolation() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.username", "[REQUIRED]")
                .withProperty("spring.datasource.password", "pw")
                .withProperty("spring.ai.openai.api-key", "key");

        assertThrows(IllegalStateException.class, new CredentialPlaceholderGuard(env)::validate);
    }

    @Test
    @DisplayName("三项都已填写 ⇒ 放行")
    void validatePassesWhenFilled() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.username", "appuser")
                .withProperty("spring.datasource.password", "s3cret")
                .withProperty("spring.ai.openai.api-key", "sk-real");

        assertDoesNotThrow(() -> new CredentialPlaceholderGuard(env).validate());
    }
}
