package com.leyon.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.entity.Record;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * AI 对话核心服务
 * 支持流式对话、Function Calling 工具调用、知识库检索、会话历史管理、会话状态持久化
 *
 * @author leyon
 */
public class ChatService {

    /** 工具返回结果最大长度，超长截断 */
    private static final int MAX_RESULT_LENGTH = 2000;

    private final ChatModel chatModel;
    private final KnowledgeService knowledgeService;
    private final ObjectMapper objectMapper;
    private final List<ToolCallback> toolCallbacks;

    /** 系统人设/提示词 */
    private String systemPrompt;
    /** 关联知识库ID列表 */
    private List<String> knowledgeIds;
    /** 对话历史消息上下文 */
    private final List<Message> conversationHistory;
    /** 聊天记录实体集合 */
    private final List<Record> chatRecords;

    public ChatService(ChatModel chatModel,
                       KnowledgeService knowledgeService,
                       ObjectMapper objectMapper,
                       String personality,
                       List<String> knowledgeIds,
                       List<ToolCallback> toolCallbacks) {
        this.chatModel = chatModel;
        this.knowledgeService = knowledgeService;
        this.objectMapper = objectMapper;
        this.systemPrompt = StringUtils.hasText(personality) ? personality : "";
        this.knowledgeIds = Objects.nonNull(knowledgeIds) ? new ArrayList<>(knowledgeIds) : new ArrayList<>();
        this.toolCallbacks = Objects.nonNull(toolCallbacks) ? new ArrayList<>(toolCallbacks) : new ArrayList<>();
        this.conversationHistory = new ArrayList<>();
        this.chatRecords = new ArrayList<>();
    }

    /**
     * 发起流式对话
     *
     * @param text 用户输入文本
     * @return 流式响应数据
     */
    public Flux<Map<String, Object>> chatStream(String text) {
        if (!StringUtils.hasText(text)) {
            return Flux.empty();
        }
        long startTime = System.currentTimeMillis();
        String effectiveSystemPrompt = buildEffectiveSystemPrompt(text);
        List<Message> messages = buildMessages(effectiveSystemPrompt, text);
        Prompt prompt = buildPrompt(messages);
        return doChatLoop(prompt, text, startTime);
    }

    /**
     * 对话主循环：处理流式返回、工具调用递归逻辑
     */
    private Flux<Map<String, Object>> doChatLoop(Prompt prompt, String userText, long startTime) {
        StringBuilder fullResponse = new StringBuilder();

        return chatModel.stream(prompt)
                .collectList()
                .flatMapMany(completeResponses -> {
                    if (completeResponses.isEmpty()) {
                        return Flux.just(createEndChunk(fullResponse.toString(), startTime));
                    }

                    ChatResponse mergedResponse = mergeResponses(completeResponses);
                    AssistantMessage assistantOutput = mergedResponse.getResult() != null
                            ? mergedResponse.getResult().getOutput()
                            : null;

                    // 检测工具调用
                    if (assistantOutput != null && !assistantOutput.getToolCalls().isEmpty()) {
                        return handleToolCalls(assistantOutput, userText, startTime);
                    }

                    // 纯文本返回
                    String content = assistantOutput != null ? assistantOutput.getText() : "";
                    if (StringUtils.hasText(content)) {
                        fullResponse.append(content);
                    }
                    saveConversation(userText, fullResponse.toString(), startTime);

                    return Flux.concat(
                            emitContentSegments(content),
                            Flux.just(createEndChunk(fullResponse.toString(), startTime))
                    );
                });
    }

    /**
     * 处理模型发起的工具调用，执行工具并继续递归对话
     */
    private Flux<Map<String, Object>> handleToolCalls(AssistantMessage assistantMessage,
                                                        String userText, long startTime) {
        var toolCalls = assistantMessage.getToolCalls();
        conversationHistory.add(assistantMessage);

        // 向前端推送工具调用通知
        List<Map<String, Object>> toolCallNotifications = new ArrayList<>();
        for (var tc : toolCalls) {
            Map<String, Object> notification = new HashMap<>();
            notification.put("type", "tool_call");
            notification.put("toolName", tc.name());
            notification.put("toolArgs", tc.arguments());
            toolCallNotifications.add(notification);
        }

        List<Message> toolResultMessages = new ArrayList<>();
        List<Map<String, Object>> toolResultList = new ArrayList<>();

        // 逐个执行工具
        for (var tc : toolCalls) {
            Map<String, Object> toolResultInfo = new HashMap<>();
            toolResultInfo.put("name", tc.name());
            try {
                ToolCallback callback = findToolCallback(tc.name());
                if (callback == null) {
                    String errorMsg = "工具【" + tc.name() + "】不存在";
                    toolResultInfo.put("result", errorMsg);
                    toolResultInfo.put("success", false);
                    toolResultMessages.add(buildToolResponse(tc.id(), tc.name(), errorMsg));
                } else {
                    Object executeResult = callback.call(tc.arguments());
                    String resultText = extractResultText(executeResult);
                    String truncateText = truncateResult(resultText);

                    toolResultInfo.put("result", truncateText);
                    toolResultInfo.put("success", true);
                    toolResultMessages.add(buildToolResponse(tc.id(), tc.name(), resultText));
                }
            } catch (ToolExecutionException e) {
                String errorMsg = "工具执行异常：" + e.getMessage();
                toolResultInfo.put("result", errorMsg);
                toolResultInfo.put("success", false);
                toolResultMessages.add(buildToolResponse(tc.id(), tc.name(), errorMsg));
            } catch (Exception e) {
                String errorMsg = "工具未知异常：" + e.getMessage();
                toolResultInfo.put("result", errorMsg);
                toolResultInfo.put("success", false);
                toolResultMessages.add(buildToolResponse(tc.id(), tc.name(), errorMsg));
            }
            toolResultList.add(toolResultInfo);
        }

        // 拼装新一轮请求消息
        List<Message> followUpMessages = new ArrayList<>();
        followUpMessages.add(new SystemMessage(systemPrompt));
        followUpMessages.addAll(conversationHistory);
        followUpMessages.addAll(toolResultMessages);

        Prompt followUpPrompt = buildPrompt(followUpMessages);

        // 流式推送：工具调用通知 -> 工具结果 -> 继续对话
        return Flux.concat(
                Flux.fromIterable(toolCallNotifications),
                Flux.fromIterable(toolResultList).map(this::wrapToolResultChunk),
                doChatLoop(followUpPrompt, userText, startTime)
        );
    }

    /**
     * 构建工具响应消息
     */
    private ToolResponseMessage buildToolResponse(String toolId, String toolName, String content) {
        return new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse(toolId, toolName, content)
        ));
    }

    /**
     * 包装工具结果向前端输出的分片结构
     */
    private Map<String, Object> wrapToolResultChunk(Map<String, Object> data) {
        Map<String, Object> chunk = new HashMap<>();
        chunk.put("type", "tool_result");
        chunk.put("data", data);
        chunk.put("streamEnd", false);
        return chunk;
    }

    /**
     * 从工具返回对象中提取纯文本结果
     */
    private String extractResultText(Object result) {
        if (result == null) {
            return "";
        }
        String rawStr = result.toString();
        try {
            Map<String, Object> jsonMap = objectMapper.readValue(rawStr, new TypeReference<Map<String, Object>>() {});
            Object content = jsonMap.get("content");
            if (content != null) {
                return content.toString();
            }
        } catch (Exception ignored) {
            // 非JSON格式，直接返回原字符串
        }
        return rawStr;
    }

    /**
     * 根据工具名称查找对应工具实例
     */
    private ToolCallback findToolCallback(String toolName) {
        for (ToolCallback callback : toolCallbacks) {
            if (callback.getToolDefinition().name().equals(toolName)) {
                return callback;
            }
        }
        return null;
    }

    /**
     * 合并多段流式响应，取最后一条完整结果
     */
    private ChatResponse mergeResponses(List<ChatResponse> responses) {
        if (responses.isEmpty()) {
            return null;
        }
        return responses.get(responses.size() - 1);
    }

    /**
     * 将文本按标点拆分，逐段流式输出
     */
    private Flux<Map<String, Object>> emitContentSegments(String content) {
        if (!StringUtils.hasText(content)) {
            return Flux.empty();
        }
        List<Map<String, Object>> segments = new ArrayList<>();
        // 中英文标点分割
        String[] parts = content.split("(?<=[。！？.!?,，；;])");
        for (String part : parts) {
            if (StringUtils.hasText(part)) {
                Map<String, Object> chunk = new HashMap<>();
                chunk.put("segment", part);
                chunk.put("streamEnd", false);
                segments.add(chunk);
            }
        }
        // 防止分割后为空，兜底返回原文本
        if (segments.isEmpty()) {
            Map<String, Object> chunk = new HashMap<>();
            chunk.put("segment", content);
            chunk.put("streamEnd", false);
            segments.add(chunk);
        }
        return Flux.fromIterable(segments);
    }

    /**
     * 构建流式结束分片
     */
    private Map<String, Object> createEndChunk(String message, long startTime) {
        long costTime = System.currentTimeMillis() - startTime;
        Map<String, Object> endChunk = new HashMap<>();
        endChunk.put("segment", "");
        endChunk.put("streamEnd", true);
        endChunk.put("message", message);
        endChunk.put("costTime", costTime);
        endChunk.put("role", "assistant");
        return endChunk;
    }

    /**
     * 超长结果截断
     */
    private String truncateResult(String result) {
        if (result == null || result.length() <= MAX_RESULT_LENGTH) {
            return result;
        }
        return result.substring(0, MAX_RESULT_LENGTH) + "...(内容过长，已截断)";
    }

    // ===================== 业务辅助方法 =====================

    /**
     * 拼接系统提示词 + 知识库检索内容
     */
    private String buildEffectiveSystemPrompt(String userInput) {
        StringBuilder promptBuilder = new StringBuilder(systemPrompt);
        if (!knowledgeIds.isEmpty()) {
            try {
                String knowledgeContext = knowledgeService.queryKnowledgeBase(userInput, knowledgeIds);
                if (StringUtils.hasText(knowledgeContext)) {
                    promptBuilder.append("\n\n以下是从知识库检索到的参考信息：\n").append(knowledgeContext);
                }
            } catch (Exception ignored) {
                // 知识库查询异常，不阻断主流程
            }
        }
        return promptBuilder.toString();
    }

    /**
     * 组装消息列表：系统提示 + 历史对话 + 当前用户消息
     */
    private List<Message> buildMessages(String systemPromptText, String userText) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPromptText));
        messages.addAll(conversationHistory);
        messages.add(new UserMessage(userText));
        return messages;
    }

    /**
     * 构建 Prompt，携带工具配置
     */
    private Prompt buildPrompt(List<Message> messages) {
        if (!toolCallbacks.isEmpty()) {
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .toolCallbacks(toolCallbacks)
                    .build();
            return new Prompt(messages, options);
        }
        return new Prompt(messages);
    }

    /**
     * 保存对话记录到上下文与实体列表
     */
    private void saveConversation(String userText, String assistantText, long startTime) {
        long costTime = System.currentTimeMillis() - startTime;
        // 维护消息上下文
        conversationHistory.add(new UserMessage(userText));
        conversationHistory.add(new AssistantMessage(assistantText));

        // 构建数据库记录实体
        Record userRecord = new Record();
        userRecord.setRole(Record.ROLE_USER);
        userRecord.setMessage(userText);
        chatRecords.add(userRecord);

        Record assistantRecord = new Record();
        assistantRecord.setRole(Record.ROLE_ASSISTANT);
        assistantRecord.setMessage(assistantText);
        assistantRecord.setCostTime(costTime);
        chatRecords.add(assistantRecord);
    }

    /**
     * 加载历史聊天记录，恢复会话上下文
     *
     * @param history 历史记录列表
     */
    public void loadChatHistory(List<Record> history) {
        if (history == null || history.isEmpty()) {
            return;
        }
        this.chatRecords.addAll(history);
        for (Record record : history) {
            if (Record.ROLE_USER == record.getRole()) {
                conversationHistory.add(new UserMessage(record.getMessage()));
            } else if (Record.ROLE_ASSISTANT == record.getRole()) {
                conversationHistory.add(new AssistantMessage(record.getMessage()));
            } else {
                conversationHistory.add(new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse("", "", record.getMessage()))));
            }
        }
    }

    /**
     * 修改人设提示词，同时清空会话历史
     *
     * @param prompt 新的系统提示词
     */
    public void changePrompt(String prompt) {
        this.systemPrompt = StringUtils.hasText(prompt) ? prompt : "";
        this.conversationHistory.clear();
        this.chatRecords.clear();
    }

    /**
     * 更新关联知识库ID
     *
     * @param ids 知识库ID集合
     */
    public void updateDataset(List<String> ids) {
        this.knowledgeIds = Objects.nonNull(ids) ? new ArrayList<>(ids) : new ArrayList<>();
    }

    /**
     * 重置会话（清空历史消息、聊天记录）
     */
    public void reset() {
        this.conversationHistory.clear();
        this.chatRecords.clear();
    }

    /**
     * 关闭会话，导出会话状态用于持久化
     *
     * @return 会话状态：聊天记录、人设、知识库ID
     */
    public Map<String, String> close() {
        Map<String, String> state = new HashMap<>();
        try {
            state.put("chatMessage", objectMapper.writeValueAsString(chatRecords));
        } catch (Exception e) {
            state.put("chatMessage", "[]");
        }
        state.put("personality", systemPrompt);
        try {
            state.put("knowledgeIds", objectMapper.writeValueAsString(knowledgeIds));
        } catch (Exception e) {
            state.put("knowledgeIds", "[]");
        }
        return state;
    }
}