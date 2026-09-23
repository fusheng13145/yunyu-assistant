package com.leyon.backend.controller;

import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI 工具字典接口单元测试
 * 覆盖：列表取自运行时注册结果并按名排序、空上下文返回空列表
 * （不校验鉴权：/api/** 由 AuthInterceptor 统一拦截，见 2.9）
 *
 * @author leyon
 */
class ToolControllerTest {

    private ToolCallback tool(String name, String description) {
        return FunctionToolCallback
                .builder(name, (Supplier<String>) name::toString)
                .description(description)
                .build();
    }

    private ToolController controllerWith(ToolCallback... callbacks) {
        ToolRegistry registry = new ToolRegistry(List.of(callbacks));
        registry.init();
        return new ToolController(registry);
    }

    @Test
    void listsRegisteredToolsSortedByName() {
        ApiResponse<List<ToolController.ToolInfo>> response = controllerWith(
                tool("web_search", "联网搜索"),
                tool("get_weather", "天气查询"))
                .list();

        assertThat(response.getData()).extracting(ToolController.ToolInfo::name)
                .containsExactly("get_weather", "web_search");
        assertThat(response.getData().get(0).description()).isEqualTo("天气查询");
    }

    @Test
    void emptyRegistry_returnsEmptyList() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getAllToolCallbacks()).thenReturn(List.of());

        assertThat(new ToolController(registry).list().getData()).isEmpty();
    }

    @Test
    void missingDescription_fallsBackToToolName() {
        ToolCallback noDesc = FunctionToolCallback
                .builder("hangup", (Supplier<String>) () -> "ok")
                .build();

        assertThat(controllerWith(noDesc).list().getData())
                .containsExactly(new ToolController.ToolInfo("hangup", "hangup"));
    }
}
