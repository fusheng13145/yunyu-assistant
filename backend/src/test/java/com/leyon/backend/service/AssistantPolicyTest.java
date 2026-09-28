package com.leyon.backend.service;

import com.leyon.backend.entity.Assistant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 助手级成本参数钳制策略单元测试（v2.54 · 候选 ㉔）
 *
 * @author leyon
 */
class AssistantPolicyTest {

    private final AssistantPolicy policy = new AssistantPolicy(new ModelCatalog());

    private Assistant assistant(String model, Double temperature, Integer maxTokens, String personality) {
        Assistant a = new Assistant();
        a.setName("测试助手");
        a.setModelName(model);
        a.setTemperature(temperature);
        a.setMaxTokens(maxTokens);
        a.setPersonality(personality);
        return a;
    }

    @Nested
    class ReadSideRuntime {

        @Test
        void keepsValuesWithinPolicy() {
            AssistantPolicy.Runtime rt = policy.runtime(
                    assistant("deepseek-reasoner", 0.7, 1024, "你是一个严谨的技术写作者"));
            assertThat(rt.model()).isEqualTo("deepseek-reasoner");
            assertThat(rt.temperature()).isEqualTo(0.7);
            assertThat(rt.maxTokens()).isEqualTo(1024);
            assertThat(rt.personality()).isEqualTo("你是一个严谨的技术写作者");
        }

        @Test
        void blankModelMeansServerDefaultAndStaysNull() {
            assertThat(policy.runtime(assistant(null, null, null, null)).model()).isNull();
            assertThat(policy.runtime(assistant("   ", null, null, null)).model()).isNull();
        }

        @Test
        void unsupportedModelFallsBackToNullInsteadOfReachingProvider() {
            // 读侧不能把"清单外的模型名"透传出去：这既是最贵的自由度，也是运行期 400 的来源
            assertThat(policy.runtime(assistant("gpt-9-mega", null, null, null)).model()).isNull();
        }

        @Test
        void clampsTemperatureIntoApiRange() {
            assertThat(policy.runtime(assistant(null, 2.5, null, null)).temperature()).isEqualTo(2.0);
            assertThat(policy.runtime(assistant(null, -1.0, null, null)).temperature()).isEqualTo(0.0);
            assertThat(policy.runtime(assistant(null, 9.9, null, null)).temperature()).isEqualTo(2.0);
        }

        @Test
        void nonFiniteTemperatureDegradesToUnset() {
            assertThat(policy.runtime(assistant(null, Double.NaN, null, null)).temperature()).isNull();
            assertThat(policy.runtime(assistant(null, Double.POSITIVE_INFINITY, null, null)).temperature()).isNull();
        }

        @Test
        void clampsMaxTokensToCap() {
            assertThat(policy.runtime(assistant(null, null, 1_000_000, null)).maxTokens())
                    .isEqualTo(AssistantPolicy.MAX_OUTPUT_TOKENS);
        }

        @Test
        void nonPositiveMaxTokensDegradesToUnset() {
            assertThat(policy.runtime(assistant(null, null, 0, null)).maxTokens()).isNull();
            assertThat(policy.runtime(assistant(null, null, -5, null)).maxTokens()).isNull();
        }

        @Test
        void truncatesOversizedPersonality() {
            String oversized = "啊".repeat(AssistantPolicy.MAX_PERSONALITY_CHARS + 500);
            AssistantPolicy.Runtime rt = policy.runtime(assistant(null, null, null, oversized));
            assertThat(rt.personality()).hasSize(AssistantPolicy.MAX_PERSONALITY_CHARS);
            assertThat(rt.personality()).startsWith("啊啊啊");
        }

        @Test
        void everyCatalogModelIsAcceptedByPolicy() {
            // 清单就是前端下拉能选到的那些值；清单内任何一项被策略拒绝，都是"界面上给得出、服务端必拒"的错位
            for (ModelCatalog.ModelInfo m : new ModelCatalog().all()) {
                assertThat(policy.runtime(assistant(m.id(), null, null, null)).model()).isEqualTo(m.id());
            }
        }
    }

    @Nested
    class WriteSideValidation {

        @Test
        void acceptsValuesWithinPolicy() {
            assertThatCode(() -> policy.validateForWrite(
                    assistant("qwen-plus", 1.0, 2048, "人设"))).doesNotThrowAnyException();
            assertThatCode(() -> policy.validateForWrite(
                    assistant(null, null, null, null))).doesNotThrowAnyException();
        }

        @Test
        void rejectsUnsupportedModelWithCatalogInMessage() {
            assertThatThrownBy(() -> policy.validateForWrite(assistant("gpt-9-mega", null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("模型")
                    .hasMessageContaining("deepseek-chat");
        }

        @Test
        void rejectsTemperatureOutOfRange() {
            assertThatThrownBy(() -> policy.validateForWrite(assistant(null, 2.5, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("温度");
            assertThatThrownBy(() -> policy.validateForWrite(assistant(null, -0.5, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("温度");
        }

        @Test
        void rejectsMaxTokensAboveCapWithTheCapInMessage() {
            assertThatThrownBy(() -> policy.validateForWrite(assistant(null, null, 999_999, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("最大输出")
                    .hasMessageContaining(String.valueOf(AssistantPolicy.MAX_OUTPUT_TOKENS));
        }

        @Test
        void rejectsNonPositiveMaxTokens() {
            assertThatThrownBy(() -> policy.validateForWrite(assistant(null, null, 0, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("最大输出");
        }

        @Test
        void rejectsOversizedPersonalityWithTheCapInMessage() {
            String oversized = "啊".repeat(AssistantPolicy.MAX_PERSONALITY_CHARS + 1);
            assertThatThrownBy(() -> policy.validateForWrite(assistant(null, null, null, oversized)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("人设")
                    .hasMessageContaining(String.valueOf(AssistantPolicy.MAX_PERSONALITY_CHARS));
        }
    }

    @Nested
    class StorageClamp {

        @Test
        void rewritesEntitySoNoUnboundedValueCanBePersisted() {
            Assistant a = assistant("gpt-9-mega", 2.5, 999_999, "啊".repeat(AssistantPolicy.MAX_PERSONALITY_CHARS + 10));
            policy.clampForStorage(a);
            assertThat(a.getModelName()).isNull();
            assertThat(a.getTemperature()).isEqualTo(2.0);
            assertThat(a.getMaxTokens()).isEqualTo(AssistantPolicy.MAX_OUTPUT_TOKENS);
            assertThat(a.getPersonality()).hasSize(AssistantPolicy.MAX_PERSONALITY_CHARS);
        }

        @Test
        void keepsUnsetFieldsUnsetSoPartialUpdateStillSkipsThem() {
            // MyBatis-Plus updateById 跳过 null 字段；把 null 填成默认值会让"只改名字"的 PUT 顺带改写模型配置
            Assistant a = assistant(null, null, null, null);
            policy.clampForStorage(a);
            assertThat(a.getModelName()).isNull();
            assertThat(a.getTemperature()).isNull();
            assertThat(a.getMaxTokens()).isNull();
            assertThat(a.getPersonality()).isNull();
        }
    }
}
