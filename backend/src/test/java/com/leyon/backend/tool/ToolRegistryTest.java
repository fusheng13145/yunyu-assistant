package com.leyon.backend.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具注册表单元测试
 * 覆盖：自动收集与按名查找；工具名重复时启动失败（防静默覆盖）；空注入容错；
 *       按助手白名单解析（空=全部、精确集合、去重、未注册名忽略且不回落全集）
 *
 * @author leyon
 */
class ToolRegistryTest {

    private ToolCallback tool(String name) {
        return FunctionToolCallback
                .builder(name, (Supplier<String>) () -> name)
                .build();
    }

    private ToolRegistry registryOf(ToolCallback... callbacks) {
        ToolRegistry registry = new ToolRegistry(List.of(callbacks));
        registry.init();
        return registry;
    }

    @Test
    void init_registersAllToolsAndLooksUpByName() {
        ToolCallback weather = tool("get_weather");
        ToolRegistry registry = registryOf(weather, tool("get_current_datetime"));

        assertThat(registry.getToolNames()).containsExactlyInAnyOrder("get_weather", "get_current_datetime");
        assertThat(registry.getTool("get_weather")).isSameAs(weather);
        assertThat(registry.getTool("missing")).isNull();
        assertThat(registry.getAllToolCallbacks()).hasSize(2);
    }

    @Test
    void init_duplicateToolName_failsFast() {
        ToolCallback first = tool("get_weather");
        ToolCallback second = tool("get_weather");

        ToolRegistry registry = new ToolRegistry(List.of(first, second));
        assertThatThrownBy(registry::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("get_weather");
    }

    @Test
    void nullToolList_yieldsEmptyRegistry() {
        ToolRegistry registry = new ToolRegistry(null);
        registry.init();
        assertThat(registry.getToolNames()).isEmpty();
        assertThat(registry.getAllToolCallbacks()).isEmpty();
    }

    @Test
    void emptyWhitelist_meansAllRegisteredTools() {
        ToolRegistry registry = registryOf(tool("get_weather"), tool("hangup"));

        assertThat(registry.resolveToolCallbacks(null)).hasSize(2);
        assertThat(registry.resolveToolCallbacks(List.of())).hasSize(2);
    }

    @Test
    void explicitWhitelist_keepsExactlyListedTools() {
        ToolCallback weather = tool("get_weather");
        ToolRegistry registry = registryOf(weather, tool("hangup"), tool("generate_image"));

        assertThat(registry.resolveToolCallbacks(List.of("hangup", "get_weather")))
                .containsExactlyInAnyOrder(weather, registry.getTool("hangup"));
        // 重复项不重复注册
        assertThat(registry.resolveToolCallbacks(List.of("get_weather", "get_weather"))).hasSize(1);
    }

    @Test
    void unknownNamesAreIgnoredAndDoNotFallBackToAll() {
        ToolRegistry registry = registryOf(tool("get_weather"), tool("hangup"));

        // 部分无效：保留有效项
        assertThat(registry.resolveToolCallbacks(List.of("get_weather", "已下线工具"))).hasSize(1);
        // 全部无效：该助手无工具可用，不回落"全部可用"（避免配置写错反而放大能力）
        assertThat(registry.resolveToolCallbacks(List.of("已下线工具", "  "))).isEmpty();
    }
}
