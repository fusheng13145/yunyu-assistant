package com.leyon.backend.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具启用条件单元测试
 * 覆盖 RequiresProperty 的两种语义：密钥型（配置非空即启用）、开关型（配置值须等于 expected）
 * 目的：确认"未配置外部依赖的工具不会出现在模型可选工具列表里"
 *
 * @author leyon
 */
class ToolAvailabilityConditionTest {

    private ApplicationContextRunner runner(Class<?>... toolClasses) {
        return new ApplicationContextRunner()
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(toolClasses);
    }

    @Test
    void weatherToolRegistersOnlyWhenApiKeyPresent() {
        runner(WeatherTool.class)
                .withPropertyValues("app.weather.api-key=")
                .run(context -> assertThat(context).doesNotHaveBean(ToolCallback.class));

        runner(WeatherTool.class)
                .withPropertyValues("app.weather.api-key=   ")
                .run(context -> assertThat(context).doesNotHaveBean(ToolCallback.class));

        runner(WeatherTool.class)
                .withPropertyValues("app.weather.api-key=k1")
                .run(context -> {
                    assertThat(context).hasSingleBean(ToolCallback.class);
                    assertThat(toolNames(context)).containsExactly("get_weather");
                });
    }

    @Test
    void searchToolRequiresBothKeyAndEndpoint() {
        runner(SearchTool.class)
                .withPropertyValues("app.search.api-key=k1", "app.search.endpoint=")
                .run(context -> assertThat(context).doesNotHaveBean(ToolCallback.class));

        runner(SearchTool.class)
                .withPropertyValues("app.search.api-key=k1", "app.search.endpoint=https://s.test/search")
                .run(context -> assertThat(toolNames(context)).containsExactly("web_search"));
    }

    @Test
    void imageToolRegistersWhenConfigured() {
        runner(ImageTool.class)
                .withPropertyValues("app.image.api-key=")
                .run(context -> assertThat(context).doesNotHaveBean(ToolCallback.class));

        runner(ImageTool.class)
                .withPropertyValues("app.image.api-key=k1",
                        "app.image.endpoint=https://img.test/v1/images/generations",
                        "app.image.model=dall-e-3")
                .run(context -> assertThat(toolNames(context)).containsExactly("generate_image"));
    }

    @Test
    void videoToolRegistersSubmitAndQueryAsTwoTools() {
        runner(VideoTool.class)
                .withPropertyValues("app.video.api-key=")
                .run(context -> assertThat(context).doesNotHaveBean(ToolCallback.class));

        runner(VideoTool.class)
                .withPropertyValues("app.video.api-key=k1",
                        "app.video.endpoint=https://vid.test/v1/videos",
                        "app.video.model=sora-2")
                .run(context -> assertThat(toolNames(context))
                        .containsExactlyInAnyOrder("generate_video", "query_video"));
    }

    @Test
    void webFetchToolIsOffByDefaultAndNeedsExplicitEnable() {
        // 属性缺省（不存在）等同于关闭
        runner(WebFetchTool.class)
                .withPropertyValues("app.webfetch.max-bytes=2000")
                .run(context -> assertThat(context).doesNotHaveBean(ToolCallback.class));

        runner(WebFetchTool.class)
                .withPropertyValues("app.webfetch.enabled=false", "app.webfetch.max-bytes=2000")
                .run(context -> assertThat(context).doesNotHaveBean(ToolCallback.class));

        // expected 比较忽略大小写
        runner(WebFetchTool.class)
                .withPropertyValues("app.webfetch.enabled=TRUE", "app.webfetch.max-bytes=2000")
                .run(context -> assertThat(toolNames(context)).containsExactly("fetch_webpage"));
    }

    @Test
    void keyFreeToolsAlwaysRegister() {
        runner(DateTimeTool.class, HangupTool.class)
                .run(context -> assertThat(toolNames(context))
                        .containsExactlyInAnyOrder("get_current_datetime", "hangup"));
    }

    /**
     * 取出上下文中全部工具名（顺带验证无重名注册）
     */
    private String[] toolNames(org.springframework.context.ApplicationContext context) {
        return context.getBeansOfType(ToolCallback.class).values().stream()
                .map(tc -> tc.getToolDefinition().name())
                .toArray(String[]::new);
    }
}
