package com.leyon.backend.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表 - 统一管理所有 AI 可调用的工具
 * 新增工具只需：1. 实现 ToolCallback 2. 注册到此表（通过 @Bean 方法）
 *
 * @author leyon
 */
@Component
public class ToolRegistry {
    private final Map<String, ToolCallback> tools = new ConcurrentHashMap<>();
    private final List<ToolCallback> toolCallbacks;

    public ToolRegistry(List<ToolCallback> allToolCallbacks) {
        this.toolCallbacks = allToolCallbacks != null ? new ArrayList<>(allToolCallbacks) : new ArrayList<>();
    }

    @PostConstruct
    public void init() {
        for (ToolCallback tc : toolCallbacks) {
            tools.put(tc.getToolDefinition().name(), tc);
        }
    }

    /**
     * 获取所有已注册的工具回调列表
     */
    public List<ToolCallback> getAllToolCallbacks() {
        return Collections.unmodifiableList(toolCallbacks);
    }

    /**
     * 根据工具名称获取对应的工具回调实例
     */
    public ToolCallback getTool(String name) {
        return tools.get(name);
    }

    /**
     * 获取所有已注册的工具名称集合
     */
    public Set<String> getToolNames() {
        return Collections.unmodifiableSet(tools.keySet());
    }
}
