package com.leyon.backend.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表 - 统一管理所有 AI 可调用的工具
 * 新增工具只需：1. 写一个 @Component 工具类 2. 暴露一个 @Bean ToolCallback 方法
 * 3. 工具依赖外部密钥/开关时，在类上标注 @RequiresProperty（配置未就绪则整体不注册）
 *
 * <p>注册表按 List&lt;ToolCallback&gt; 自动收集容器内全部工具，无需在此登记；
 * 工具名重复属配置错误，启动即失败（避免后注册者静默覆盖先注册者）。
 *
 * @author leyon
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolCallback> tools = new ConcurrentHashMap<>();
    private final List<ToolCallback> toolCallbacks;

    public ToolRegistry(List<ToolCallback> allToolCallbacks) {
        this.toolCallbacks = allToolCallbacks != null ? new ArrayList<>(allToolCallbacks) : new ArrayList<>();
    }

    @PostConstruct
    public void init() {
        for (ToolCallback tc : toolCallbacks) {
            String name = tc.getToolDefinition().name();
            if (tools.putIfAbsent(name, tc) != null) {
                throw new IllegalStateException("AI 工具名重复：" + name + "，请检查工具类的 @Bean 方法");
            }
        }
        log.info("AI 工具注册完成，共 {} 个可用工具：{}", tools.size(), new TreeSet<>(tools.keySet()));
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
