package com.leyon.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * save_memory 工具的按用户闭包工厂（v2.85 · C-161，收口候选 ⑫）
 *
 * 工具本体无法是无状态单例——call(String) 不带用户身份，"是谁存的"必须在装配时闭包进去
 * （与 ToolQuotaGuard 同一解法：userId 来自装配时的握手身份，不来自模型）。
 * 工具入参是 {"content":"要记住的事"}；写入路径只有它（先规则化后模型化的第一步）。
 *
 * @author leyon
 */
@Service
public class MemoryToolService {

    /** 工具名：模型侧以这个名字感知并调用 */
    public static final String TOOL_NAME = "save_memory";

    private static final Logger log = LoggerFactory.getLogger(MemoryToolService.class);

    private final UserMemoryService userMemoryService;
    public MemoryToolService(UserMemoryService userMemoryService) {
        this.userMemoryService = userMemoryService;
    }

    /**
     * 为指定用户产出 save_memory 工具回调；无 userId（理论上不可达，握手即鉴权）返回空
     */
    public Optional<ToolCallback> toolFor(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        // inputType 用专用 record：Spring AI 会把工具入参 JSON 反序列化成它，
        // String 形状接不住 {"content":...} 对象 JSON（真机实证抓到）
        return Optional.of(FunctionToolCallback
                .builder(TOOL_NAME, saveFunctionFor(userId))
                .description("把用户明确要求记住的长期信息（偏好、背景、约定）保存到跨会话记忆。"
                        + "仅在用户明确说'记住…'或明确给出需要长期保留的信息时调用；入参为 JSON：{\"content\": \"要记住的事\"}")
                .inputType(SaveMemoryInput.class)
                .build());
    }

    /** 把工具入参接进执行体的绑定函数（显式命名方法，避免内联泛型 lambda 的解析歧义） */
    private java.util.function.Function<SaveMemoryInput, String> saveFunctionFor(String userId) {
        return input -> saveFor(userId, input);
    }

    /** save_memory 的入参形状（Spring AI 按此反序列化工具入参 JSON） */
    public record SaveMemoryInput(String content) {
    }

    /**
     * 工具执行体：解析入参并落库；失败收敛为文本结果（与工具失败出口同一形状，模型可继续对话）
     */
    private String saveFor(String userId, SaveMemoryInput input) {
        String content = input == null ? "" : input.content();
        if (content.isBlank()) {
            return "记忆内容为空，未保存";
        }
        try {
            userMemoryService.save(userId, content);
            return "已记住：" + content;
        } catch (Exception e) {
            log.warn("save_memory 执行失败，用户ID:{}：{}", userId, e.getMessage());
            return "记忆保存失败：" + e.getMessage();
        }
    }

}
