package com.leyon.backend.controller;

import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.tool.ToolRegistry;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * AI 工具字典接口
 * 下发当前实际注册（即配置已就绪）的工具列表，供前端"可用工具"白名单勾选
 *
 * <p>不维护第二份工具清单：数据取自 ToolRegistry 运行时注册结果，
 * 因此未配置 Key 的工具不会出现在列表里，新增工具类也无需改动本接口。
 * 描述沿用工具注册时的说明文案（同时是给模型看的提示词）。
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;

    public ToolController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 工具信息
     *
     * @param name        工具名（写入助手 tools 白名单即用它）
     * @param description 能力说明
     */
    public record ToolInfo(String name, String description) {}

    /**
     * 获取当前可用工具列表（按工具名排序）
     */
    @GetMapping
    public ApiResponse<List<ToolInfo>> list() {
        List<ToolInfo> tools = toolRegistry.getAllToolCallbacks().stream()
                .map(tc -> tc.getToolDefinition())
                .map(definition -> new ToolInfo(definition.name(), description(definition)))
                .sorted(Comparator.comparing(ToolInfo::name))
                .toList();
        return ApiResponse.success(tools);
    }

    /**
     * 描述为空时回退工具名，避免前端勾选框无文案
     */
    private String description(ToolDefinition definition) {
        String description = definition.description();
        return description == null || description.isBlank() ? definition.name() : description;
    }
}
