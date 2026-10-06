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
 * 三条对话链路统一走 {@link #resolveToolCallbacks(java.util.Collection)} 取工具，
 * 以便按助手白名单裁剪能力。
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

    /**
     * 按助手的工具白名单解析工具列表（v2.28 支持按助手裁剪能力）
     *
     * <p>语义约定：白名单为空（未配置）= 全部可用工具，保证既有助手行零迁移即行为不变；
     * 显式列表 = 精确集合，其中未注册的工具名（如服务商 Key 被撤下、名称写错）忽略并记 WARN，
     * 全部无效时该助手无工具可用——不回落"全部可用"，避免配置错误反而放大权限。
     *
     * @param allowedNames 白名单工具名集合，可为 null/空
     * @return 供 ChatService 注入的工具列表
     */
    /**
     * 语音场景的工具解析：在白名单裁剪结果之上**强制携带 hangup**（v2.83 · C-159，收口候选 ⑬）。
     * hangup 是 LLM 主动挂断通话的唯一通路——语音通话被裁掉它之后，"用户说完了、模型也确认了"
     * 的通话只能靠 ASR 静默或用户手点结束，挂断承诺落空。清单为空 = 未配置 = 全部可用工具，
     * hangup 天然在内；显式白名单缺它时在此补挂（重复时以白名单内位置为准，不重复注册）。
     */
    public List<ToolCallback> resolveVoiceToolCallbacks(Collection<String> allowedNames) {
        List<ToolCallback> resolved = new ArrayList<>(resolveToolCallbacks(allowedNames));
        boolean hasHangup = resolved.stream()
                .anyMatch(cb -> "hangup".equals(cb.getToolDefinition().name()));
        if (!hasHangup) {
            ToolCallback hangup = tools.get("hangup");
            if (hangup != null) {
                resolved.add(hangup);
            }
        }
        return resolved;
    }

    public List<ToolCallback> resolveToolCallbacks(Collection<String> allowedNames) {
        if (allowedNames == null || allowedNames.isEmpty()) {
            return getAllToolCallbacks();
        }
        List<ToolCallback> picked = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String name : allowedNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            ToolCallback tool = tools.get(name.trim());
            if (tool == null) {
                unknown.add(name.trim());
            } else if (!picked.contains(tool)) {
                picked.add(tool);
            }
        }
        if (!unknown.isEmpty()) {
            log.warn("助手工具白名单含未注册的工具名（已忽略）：{}，当前可用工具：{}", unknown, getToolNames());
        }
        return picked;
    }
}
