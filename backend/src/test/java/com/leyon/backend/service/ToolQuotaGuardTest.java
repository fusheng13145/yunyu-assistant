package com.leyon.backend.service;

import com.leyon.backend.common.QuotaExceededException;
import com.leyon.backend.entity.Quota;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具配额闸门单测（v2.82 · C-158，收口候选 ⑩）
 * 覆盖：0 关闭维度、放行时先计量后委托、超限拒绝且不触真身、按工具名分格。
 * 判据工具用真实 ToolDefinition（FunctionToolCallback 的 builder 形态），装饰对装配点无感知。
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolQuotaGuardTest {

    @Mock
    private QuotaService quotaService;

    private ToolQuotaGuard guard;

    @BeforeEach
    void setUp() {
        guard = new ToolQuotaGuard(quotaService, 100);
    }

    /** 可执行的假工具回调：call 记录"真身已触"并返回固定结果 */
    private static final class FakeCallback implements org.springframework.ai.tool.ToolCallback {
        private final String name;
        boolean invoked;

        private FakeCallback(String name) {
            this.name = name;
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return org.springframework.ai.tool.definition.ToolDefinition.builder()
                    .name(name).description("test").inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput) {
            invoked = true;
            return "ok";
        }
    }

    @Test
    void zeroLimit_disablesDimension_noWrapping() {
        ToolQuotaGuard disabled = new ToolQuotaGuard(quotaService, 0);
        FakeCallback callback = new FakeCallback("web_search");

        List<org.springframework.ai.tool.ToolCallback> result =
                disabled.guard("u-1", List.of(callback));

        // 0 = 关闭维度：原样返回原列表（同内容同序），不包装、不建账本行
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isSameAs(callback);
        org.springframework.ai.tool.ToolCallback guarded = result.get(0);
        guarded.call("{}");
        assertThat(callback.invoked).isTrue();
        verify(quotaService, never()).checkToolCall(any(), any(), anyInt());
    }

    @Test
    void underLimit_metersThenDelegates() {
        FakeCallback callback = new FakeCallback("web_search");
        when(quotaService.getEffective("u-1")).thenReturn(new Quota());

        List<org.springframework.ai.tool.ToolCallback> result = guard.guard("u-1", List.of(callback));
        result.get(0).call("{\"q\":\"x\"}");

        assertThat(callback.invoked).isTrue();
        // 计量分格按"用户 × 工具名"，上限取构造注入的环境变量值
        verify(quotaService).checkToolCall("u-1", "web_search", 100);
    }

    @Test
    void overLimit_throwsWithoutInvokingRealTool() {
        FakeCallback callback = new FakeCallback("generate_image");
        when(quotaService.getEffective("u-1")).thenReturn(new Quota());
        org.mockito.Mockito.doThrow(new QuotaExceededException("工具调用已达当日上限"))
                .when(quotaService).checkToolCall(eq("u-1"), eq("generate_image"), anyInt());

        List<org.springframework.ai.tool.ToolCallback> result = guard.guard("u-1", List.of(callback));
        org.springframework.ai.tool.ToolCallback guarded = result.get(0);

        assertThatThrownBy(() -> guarded.call("{}"))
                .isInstanceOf(QuotaExceededException.class);
        // 超限拒绝不触真身：昂贵外部调用不发生、不产生任何外部开销
        assertThat(callback.invoked).isFalse();
    }

    @Test
    void perToolIsolation_gridByToolName() {
        FakeCallback search = new FakeCallback("web_search");
        FakeCallback image = new FakeCallback("generate_image");
        when(quotaService.getEffective("u-1")).thenReturn(new Quota());
        org.mockito.Mockito.doThrow(new QuotaExceededException("超限"))
                .when(quotaService).checkToolCall(eq("u-1"), eq("generate_image"), anyInt());

        List<org.springframework.ai.tool.ToolCallback> result = guard.guard("u-1", List.of(search, image));
        // generate_image 超限 → 该工具的调用抛配额异常
        assertThatThrownBy(() -> result.get(1).call("{}")).isInstanceOf(QuotaExceededException.class);
        assertThat(image.invoked).isFalse();
        // web_search 的独立格子不受影响
        result.get(0).call("{}");
        assertThat(search.invoked).isTrue();
        verify(quotaService).checkToolCall("u-1", "generate_image", 100);
        verify(quotaService).checkToolCall("u-1", "web_search", 100);
    }
}
