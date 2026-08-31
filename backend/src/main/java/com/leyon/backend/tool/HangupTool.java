package com.leyon.backend.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 挂断工具函数
 * 供 LLM 判定对话自然结束（用户道别、无需继续）时调用，触发通话挂断
 *
 * @author leyon
 */
@Component
public class HangupTool {

    /**
     * 注册 AI 工具回调：挂断通话
     *
     * @return 工具回调实例
     */
    @Bean
    ToolCallback hangupFunction() {
        return FunctionToolCallback
                .builder("hangup", (Supplier<String>) this::hangup)
                .description("当对话自然结束、用户已表示再见或明确无需继续对话时调用，用于结束当前通话")
                .build();
    }

    /**
     * 执行挂断动作（实际挂断由上层通过工具调用信号触发）
     *
     * @return 工具执行结果描述
     */
    public String hangup() {
        return "对话已结束，即将挂断通话";
    }
}
