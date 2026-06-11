package com.leyon.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.entity.Record;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import reactor.core.publisher.Flux;

public class ChatService {

    private final ChatModel chatModel;
    private final KnowledgeService knowledgeService;
    private final ObjectMapper objectMapper;
    private final List<ToolCallback> toolCallbacks;
    private String systemPrompt;
    private List<String> knowledgeIds;
    private final List<Message> conversationHistory;
    private final List<Record> chatRecords;

    public ChatService(ChatModel chatModel, KnowledgeService knowledgeService,
                       ObjectMapper objectMapper, String personality, List<String> knowledgeIds,
                       List<ToolCallback> toolCallbacks) {
        this.chatModel = chatModel;
        this.knowledgeService = knowledgeService;
        this.objectMapper = objectMapper;
        this.systemPrompt = personality != null ? personality : "";
        this.knowledgeIds = knowledgeIds != null ? knowledgeIds : new ArrayList<>();
        this.toolCallbacks = toolCallbacks != null ? toolCallbacks : new ArrayList<>();
        this.conversationHistory = new ArrayList<>();
        this.chatRecords = new ArrayList<>();
    }

    public Flux<Map<String, Object>> chatStream(String text) {
        long startTime = System.currentTimeMillis();
        String effectiveSystemPrompt = buildEffectiveSystemPrompt(text);
        List<Message> messages = buildMessages(effectiveSystemPrompt, text);
        Prompt prompt = buildPrompt(messages);
        return doChatLoop(prompt, text, startTime);
    }

    /**
     * 核心对话循环：处理普通文本流 + Function Calling 工具调用循环
     */
    private Flux<Map<String, Object>> doChatLoop(Prompt prompt, String userText, long startTime) {
        StringBuilder fullResponse = new StringBuilder();

        return chatModel.stream(prompt)
                .collectList()
                .flatMapMany(completeResponses -> {
                    if (completeResponses.isEmpty()) {
                        return Flux.just(createEndChunk(fullResponse.toString(), startTime));
                    }

                    ChatResponse merged = mergeResponses(completeResponses);
                    AssistantMessage assistantOutput = merged.getResult() != null
                            ? merged.getResult().getOutput() : null;

                    // 检查是否有工具调用
                    if (assistantOutput != null && assistantOutput.getToolCalls() != null
                            && !assistantOutput.getToolCalls().isEmpty()) {
                        return handleToolCalls(assistantOutput, userText, startTime);
                    }

                    // 无工具调用：正常返回文本内容
                    String content = assistantOutput != null ? assistantOutput.getText() : "";
                    if (content != null && !content.isEmpty()) {
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
     * 处理工具调用请求：执行工具 → 回传结果 → 再次调用模型 → 循环直到获得最终文本
     */
    @SuppressWarnings("unchecked")
    private Flux<Map<String, Object>> handleToolCalls(AssistantMessage assistantMessage,
                                                        String userText, long startTime) {
        var toolCalls = assistantMessage.getToolCalls();
        conversationHistory.add(assistantMessage);

        // 发送工具调用通知给前端
        List<Map<String, Object>> toolCallNotifications = new ArrayList<>();
        for (var tc : toolCalls) {
            Map<String, Object> notification = new HashMap<>();
            notification.put("type", "tool_call");
            notification.put("toolName", tc.name());
            notification.put("toolArgs", tc.arguments());
            toolCallNotifications.add(notification);
        }

        // 执行所有工具调用并收集结果
        List<Message> toolResultMessages = new ArrayList<>();
        List<Map<String, Object>> toolResults = new ArrayList<>();

        for (var tc : toolCalls) {
            Map<String, Object> toolResultInfo = new HashMap<>();
            toolResultInfo.put("name", tc.name());
            try {
                ToolCallback callback = findToolCallback(tc.name());
                if (callback == null) {
                    String errorMsg = "工具 [" + tc.name() + "] 未找到";
                    toolResultInfo.put("result", errorMsg);
                    toolResultInfo.put("success", "false");
                    toolResultMessages.add(new ToolResponseMessage(List.of(
                            new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), errorMsg))));
                } else {
                    // ToolCallback.call 接收工具参数字符串
                    var result = callback.call(tc.arguments());
                    // ToolCallback.call 返回 ToolCallbackResponse，提取结果字符串
                    String resultText = extractResultText(result);
                    toolResultInfo.put("result", truncateResult(resultText));
                    toolResultInfo.put("success", "true");
                    toolResultMessages.add(new ToolResponseMessage(List.of(
                            new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), resultText))));
                }
            } catch (ToolExecutionException e) {
                String errorMsg = "工具执行错误: " + e.getMessage();
                toolResultInfo.put("result", errorMsg);
                toolResultInfo.put("success", "false");
                toolResultMessages.add(new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), errorMsg))));
            } catch (Exception e) {
                String errorMsg = "工具内部异常: " + e.getMessage();
                toolResultInfo.put("result", errorMsg);
                toolResultInfo.put("success", "false");
                toolResultMessages.add(new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), errorMsg))));
            }
            toolResults.add(toolResultInfo);
        }

        // 构建新的消息列表继续对话
        List<Message> followUpMessages = new ArrayList<>();
        followUpMessages.add(new SystemMessage(systemPrompt));
        followUpMessages.addAll(conversationHistory);
        followUpMessages.addAll(toolResultMessages);

        Prompt followUpPrompt = buildPrompt(followUpMessages);

        return Flux.concat(
                Flux.fromIterable(toolCallNotifications),
                Flux.fromIterable(toolResults).map(tr -> {
                    Map<String, Object> chunk = new HashMap<>();
                    chunk.put("type", "tool_result");
                    chunk.put("data", tr);
                    chunk.put("streamEnd", false);
                    return chunk;
                }),
                doChatLoop(followUpPrompt, userText, startTime)
        );
    }

    /**
     * 从 ToolCallback 的返回值中提取结果文本
     */
    private String extractResultText(Object result) {
        if (result == null) {
            return "";
        }
        String str = result.toString();
        // 如果结果是 JSON 字符串，尝试提取 content 字段
        try {
            Map<String, Object> parsed = objectMapper.readValue(str, new TypeReference<Map<String, Object>>() {});
            Object content = parsed.get("content");
            if (content != null) {
                return content.toString();
            }
        } catch (Exception ignored) {
            // 不是 JSON，直接返回原始字符串
        }
        return str;
    }

    private ToolCallback findToolCallback(String toolName) {
        for (ToolCallback tc : toolCallbacks) {
            if (tc.getToolDefinition().name().equals(toolName)) {
                return tc;
            }
        }
        return null;
    }

    private ChatResponse mergeResponses(List<ChatResponse> responses) {
        if (responses.isEmpty()) {
            return null;
        }
        return responses.get(responses.size() - 1);
    }

    private Flux<Map<String, Object>> emitContentSegments(String content) {
        if (content == null || content.isEmpty()) {
            return Flux.empty();
        }
        List<Map<String, Object>> segments = new ArrayList<>();
        String[] parts = content.split("(?<=[。！？.!?,，；;])");
        for (String part : parts) {
            if (!part.isEmpty()) {
                Map<String, Object> chunk = new HashMap<>();
                chunk.put("segment", part);
                chunk.put("streamEnd", false);
                segments.add(chunk);
            }
        }
        if (segments.isEmpty()) {
            Map<String, Object> chunk = new HashMap<>();
            chunk.put("segment", content);
            chunk.put("streamEnd", false);
            segments.add(chunk);
        }
        return Flux.fromIterable(segments);
    }

    private Map<String, Object> createEndChunk(String message, long costTime) {
        Map<String, Object> endChunk = new HashMap<>();
        endChunk.put("segment", "");
        endChunk.put("streamEnd", true);
        endChunk.put("message", message);
        endChunk.put("costTime", costTime);
        endChunk.put("role", "assistant");
        return endChunk;
    }

    private String truncateResult(String result) {
        int maxLength = 2000;
        if (result.length() > maxLength) {
            return result.substring(0, maxLength) + "...(结果过长已截断)";
        }
        return result;
    }

    // ==================== 辅助方法 ====================

    private String buildEffectiveSystemPrompt(String text) {
        String effectiveSystemPrompt = systemPrompt;
        if (knowledgeIds != null && !knowledgeIds.isEmpty()) {
            try {
                String knowledgeContext = knowledgeService.queryKnowledgeBase(text, knowledgeIds);
                if (!knowledgeContext.isEmpty()) {
                    effectiveSystemPrompt += "\n\n以下是从知识库中检索到的相关信息：\n" + knowledgeContext;
                }
            } catch (Exception ignored) {
            }
        }
        return effectiveSystemPrompt;
    }

    private List<Message> buildMessages(String systemPromptText, String userText) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPromptText));
        messages.addAll(conversationHistory);
        messages.add(new UserMessage(userText));
        return messages;
    }

    private Prompt buildPrompt(List<Message> messages) {
        if (!toolCallbacks.isEmpty()) {
            OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder();
            optionsBuilder.toolCallbacks(toolCallbacks);
            return new Prompt(messages, optionsBuilder.build());
        }
        return new Prompt(messages);
    }

    private void saveConversation(String userText, String assistantText, long costTime) {
        conversationHistory.add(new UserMessage(userText));
        conversationHistory.add(new AssistantMessage(assistantText));

        Record userMsg = new Record();
        userMsg.setRole(Record.ROLE_USER);
        userMsg.setMessage(userText);
        chatRecords.add(userMsg);

        Record assistantMsg = new Record();
        assistantMsg.setRole(Record.ROLE_ASSISTANT);
        assistantMsg.setMessage(assistantText);
        assistantMsg.setCostTime(costTime);
        chatRecords.add(assistantMsg);
    }

    public void loadChatHistory(List<Record> history) {
        if (history == null) {
            return;
        }
        this.chatRecords.addAll(history);
        for (Record msg : history) {
            if (msg.getRole() != null && msg.getRole() == Record.ROLE_USER) {
                conversationHistory.add(new UserMessage(msg.getMessage()));
            } else if (msg.getRole() != null && msg.getRole() == Record.ROLE_ASSISTANT) {
                conversationHistory.add(new AssistantMessage(msg.getMessage()));
            } else {
                conversationHistory.add(new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse("", "", msg.getMessage()))));
            }
        }
    }

    public void changePrompt(String prompt) {
        this.systemPrompt = prompt != null ? prompt : "";
        this.conversationHistory.clear();
        this.chatRecords.clear();
    }

    public void updateDataset(List<String> ids) {
        this.knowledgeIds = ids != null ? ids : new ArrayList<>();
    }

    public void reset() {
        this.conversationHistory.clear();
        this.chatRecords.clear();
    }

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
