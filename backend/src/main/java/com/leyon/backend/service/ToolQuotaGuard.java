package com.leyon.backend.service;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具调用的配额闸门（v2.82 · C-158，收口候选 ⑩）。
 *
 * 为什么是装饰器而不是在 ChatService 里判：三条对话通道（文本 WS / 语音 WS / 开放 SSE）
 * 各自装配工具列表，只有把配额判据织进工具回调本身，"每一条工具执行都先过闸"才是
 * 结构保证而不是约定；Limit 为零表示关闭该维度（不建账本行）。
 *
 * 计量维度：daily_tool:{toolName}——按工具分格（web_search 与 generate_image 的成本
 * 不同，混在一格会让便宜工具替昂贵工具背限次）。上限来自环境变量默认值（QUOTA_DAILY_TOOL_CALL_LIMIT），
 * 管理端 quotas 表暂不管理该维度（表列迁移留给真有多级诉求时）。
 *
 * 拒绝形状与既有工具失败同一出口：抛 QuotaExceededException，由 ChatService.handleToolCalls
 * 的逐工具 catch 收敛为 success=false 的 tool_result——配额拒绝是"这次工具失败"，不是"这轮对话失败"。
 *
 * @author leyon
 */
@Component
public class ToolQuotaGuard {

    private final QuotaService quotaService;
    private final int dailyToolCallLimit;

    public ToolQuotaGuard(QuotaService quotaService,
                          @Value("${app.quota.daily-tool-call-limit:200}") int dailyToolCallLimit) {
        this.quotaService = quotaService;
        this.dailyToolCallLimit = dailyToolCallLimit;
    }

    /**
     * 给回调列表套上配额闸门；名单顺序保持不变（装配点对包装无感知）
     */
    public List<ToolCallback> guard(String userId, List<ToolCallback> callbacks) {
        if (dailyToolCallLimit <= 0) {
            // 0 = 关闭该维度：不包装、不建账本行
            return callbacks;
        }
        List<ToolCallback> guarded = new ArrayList<>(callbacks.size());
        for (ToolCallback callback : callbacks) {
            guarded.add(new GuardedCallback(userId, callback, callback.getToolDefinition().name()));
        }
        return guarded;
    }

    /** 配额判据：按用户 + 工具名分格的日限次；拒绝以既有 QuotaExceededException 出口收敛 */
    private void check(String userId, String toolName) {
        quotaService.checkToolCall(userId, toolName, dailyToolCallLimit);
    }

    /**
     * 装饰器：call() 先过配额闸再委托真身；getToolDefinition() 直通真身（模型清单不受包装影响）
     */
    private final class GuardedCallback implements ToolCallback {

        private final String userId;
        private final ToolCallback delegate;
        private final String toolName;

        private GuardedCallback(String userId, ToolCallback delegate, String toolName) {
            this.userId = userId;
            this.delegate = delegate;
            this.toolName = toolName;
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public String call(String toolInput) {
            check(userId, toolName);
            return delegate.call(toolInput);
        }

        @Override
        public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
            check(userId, toolName);
            return delegate.call(toolInput, toolContext);
        }
    }
}
