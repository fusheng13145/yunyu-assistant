package com.leyon.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.entity.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.*;
import java.util.function.Consumer;

/**
 * AI 对话核心服务
 * 支持流式对话、Function Calling 工具调用、知识库检索、会话历史管理、会话状态持久化
 *
 * @author leyon
 */
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 工具返回结果最大长度，超长截断 */
    private static final int MAX_RESULT_LENGTH = 2000;

    /** 单轮对话工具调用最大迭代轮次，防止 LLM 持续调用工具导致无限递归 */
    private static final int MAX_TOOL_ITERATIONS = 5;

    /** 注入 LLM 上下文的对话历史最大消息条数，防止无限增长 */
    private static final int MAX_HISTORY_MESSAGES = 60;

    /** 挂断工具名 */
    private static final String TOOL_HANGUP = "hangup";

    /**
     * 单条用户输入的最大字符数（v2.29）：配额按条数计量，单条不限长则一条超长输入即可打穿成本。
     * 刻意低于 Tomcat 默认入站文本帧上限 8KB（中文 UTF-8 三字节，2000 字≈6KB），让超长输入是业务错误而非连接被容器掐断。
     */
    public static final int MAX_INPUT_CHARS = 2000;

    private final ModelAdapter modelAdapter;
    private final KnowledgeProvider knowledgeProvider;
    private final ObjectMapper objectMapper;
    private final List<ToolCallback> toolCallbacks;

    /** 系统人设/提示词 */
    private String systemPrompt;
    /** 关联知识库ID列表 */
    private List<String> knowledgeIds;
    /** 对话历史消息上下文 */
    private final List<Message> conversationHistory;
    /** 本次会话新产生的聊天记录实体集合（用于落库） */
    private final List<Record> chatRecords;
    /** 最近一次知识库检索命中结果（供 query_end 携带引用信息） */
    private KnowledgeProvider.KnowledgeHit lastKnowledgeHit = KnowledgeProvider.KnowledgeHit.empty();
    /** 最近一次对话的 Token 用量（供 query_end 携带诊断信息） */
    private long lastPromptTokens;
    private long lastCompletionTokens;
    /** 挂断监听器：LLM 调用 hangup 工具时触发（语音通话场景） */
    private Consumer<String> hangupListener;
    /** 自定义模型名（助手级，覆盖全局配置） */
    private String modelName;
    /** 自定义温度（助手级，覆盖全局配置） */
    private Double temperature;
    /** 自定义最大输出 Token（助手级，覆盖全局配置） */
    private Integer maxTokens;

    public ChatService(ModelAdapter modelAdapter,
                       KnowledgeProvider knowledgeProvider,
                       ObjectMapper objectMapper,
                       String personality,
                       List<String> knowledgeIds,
                       List<ToolCallback> toolCallbacks) {
        this.modelAdapter = modelAdapter;
        this.knowledgeProvider = knowledgeProvider;
        this.objectMapper = objectMapper;
        this.systemPrompt = StringUtils.hasText(personality) ? personality : "";
        this.knowledgeIds = Objects.nonNull(knowledgeIds) ? new ArrayList<>(knowledgeIds) : new ArrayList<>();
        this.toolCallbacks = Objects.nonNull(toolCallbacks) ? new ArrayList<>(toolCallbacks) : new ArrayList<>();
        this.conversationHistory = new ArrayList<>();
        this.chatRecords = new ArrayList<>();
    }

    /**
     * 设置挂断监听器（语音通话场景使用，LLM 调用 hangup 工具时触发）
     *
     * @param listener 挂断监听器，参数为挂断原因/描述
     */
    public void setHangupListener(Consumer<String> listener) {
        this.hangupListener = listener;
    }

    /**
     * 设置助手级模型参数（覆盖全局默认配置）
     *
     * @param modelName   模型名（可为空）
     * @param temperature 温度 0-2（可为空）
     * @param maxTokens   最大输出 Token（可为空）
     */
    public void setModelParams(String modelName, Double temperature, Integer maxTokens) {
        this.modelName = modelName;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
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
        return doChatLoop(prompt, text, startTime, 0);
    }

    /**
     * 对话主循环：真正的响应式流式处理，逐块推送文本，末尾检测工具调用
     */
    private Flux<Map<String, Object>> doChatLoop(Prompt prompt, String userText, long startTime, int depth) {
        return Flux.create(sink -> {
            StringBuilder fullResponse = new StringBuilder();
            List<ChatResponse> allResponses = new ArrayList<>();

            modelAdapter.stream(prompt).subscribe(
                    chunk -> {
                        allResponses.add(chunk);
                        String text = chunk.getResult() != null && chunk.getResult().getOutput() != null
                                ? chunk.getResult().getOutput().getText() : "";
                        if (StringUtils.hasText(text)) {
                            fullResponse.append(text);
                            Map<String, Object> segment = new HashMap<>();
                            segment.put("segment", text);
                            segment.put("streamEnd", false);
                            sink.next(segment);
                        }
                    },
                    sink::error,
                    () -> {
                        if (allResponses.isEmpty()) {
                            sink.next(createEndChunk(fullResponse.toString(), startTime));
                            sink.complete();
                            return;
                        }
                        ChatResponse mergedResponse = mergeResponses(allResponses);
                        // 提取 Token 用量（供 query_end 诊断信息）
                        extractUsage(allResponses);
                        AssistantMessage assistantOutput = mergedResponse.getResult() != null
                                ? mergedResponse.getResult().getOutput()
                                : null;

                        // 检测工具调用
                        if (assistantOutput != null && !assistantOutput.getToolCalls().isEmpty()) {
                            // 达到工具调用迭代上限时不再递归，直接结束本轮，防止无限循环
                            if (depth >= MAX_TOOL_ITERATIONS) {
                                saveConversation(userText, fullResponse.toString(), startTime);
                                sink.next(createEndChunk(fullResponse.toString(), startTime));
                                sink.complete();
                                return;
                            }
                            saveConversation(userText, fullResponse.toString(), startTime);
                            handleToolCalls(assistantOutput, userText, startTime, depth).subscribe(
                                    sink::next,
                                    sink::error,
                                    sink::complete
                            );
                        } else {
                            saveConversation(userText, fullResponse.toString(), startTime);
                            sink.next(createEndChunk(fullResponse.toString(), startTime));
                            sink.complete();
                        }
                    }
            );
        });
    }

    /**
     * 处理模型发起的工具调用，执行工具并继续递归对话
     */
    private Flux<Map<String, Object>> handleToolCalls(AssistantMessage assistantMessage,
                                                        String userText, long startTime, int depth) {
        var toolCalls = assistantMessage.getToolCalls();
        conversationHistory.add(assistantMessage);

        // 检测挂断工具调用，触发挂断监听器（语音通话场景）
        if (hangupListener != null) {
            for (var tc : toolCalls) {
                if (TOOL_HANGUP.equals(tc.name())) {
                    hangupListener.accept("LLM 判定对话结束，主动挂断");
                    break;
                }
            }
        }

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

        // 流式推送：工具调用通知 -> 工具结果 -> 继续对话（深度+1）
        return Flux.concat(
                Flux.fromIterable(toolCallNotifications),
                Flux.fromIterable(toolResultList).map(this::wrapToolResultChunk),
                doChatLoop(followUpPrompt, userText, startTime, depth + 1)
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
     * 从流式响应中提取 Token 用量（usage 通常位于最后一个 chunk 的 metadata）
     */
    private void extractUsage(List<ChatResponse> responses) {
        lastPromptTokens = 0;
        lastCompletionTokens = 0;
        for (int i = responses.size() - 1; i >= 0; i--) {
            ChatResponse response = responses.get(i);
            try {
                if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                    Usage usage = response.getMetadata().getUsage();
                    if (usage.getPromptTokens() != null) {
                        lastPromptTokens = usage.getPromptTokens();
                    }
                    if (usage.getCompletionTokens() != null) {
                        lastCompletionTokens = usage.getCompletionTokens();
                    }
                    if (lastPromptTokens > 0 || lastCompletionTokens > 0) {
                        return;
                    }
                }
            } catch (Exception ignored) {
                // 某些响应无 usage 元数据，忽略
            }
        }
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
        // 携带知识库引用信息（docCount / docName / failed）
        // failed=true 表示"本轮检索失败"，与 docCount=0 的"知识库没有相关内容"是两种不同状态
        Map<String, Object> knowledgebase = new HashMap<>();
        knowledgebase.put("docCount", lastKnowledgeHit.docCount());
        knowledgebase.put("docName", lastKnowledgeHit.docNames());
        knowledgebase.put("failed", lastKnowledgeHit.failed());
        endChunk.put("knowledgebase", knowledgebase);
        // 携带 Token 用量（供调试面板诊断）
        Map<String, Object> tokenUsage = new HashMap<>();
        tokenUsage.put("promptTokens", lastPromptTokens);
        tokenUsage.put("completionTokens", lastCompletionTokens);
        endChunk.put("tokenUsage", tokenUsage);
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
        // 重置命中信息
        lastKnowledgeHit = KnowledgeProvider.KnowledgeHit.empty();
        if (!knowledgeIds.isEmpty()) {
            try {
                KnowledgeProvider.KnowledgeHit hit = knowledgeProvider.queryKnowledgeBaseWithDetail(userInput, knowledgeIds);
                if (StringUtils.hasText(hit.context())) {
                    promptBuilder.append("\n\n以下是从知识库检索到的参考信息：\n").append(hit.context());
                }
                lastKnowledgeHit = hit;
            } catch (Exception e) {
                // 知识库检索异常不阻断主流程，但要把本轮标记为"检索失败"，否则回答照常输出、引用为 0 篇，
                // 与"知识库确实没有相关内容"完全同形（v2.39 吞错显性化）
                log.warn("知识库检索异常，本条回复不带参考上下文：{}（{}）", e.getMessage(), e.getClass().getSimpleName());
                lastKnowledgeHit = KnowledgeProvider.KnowledgeHit.failure();
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
     * 构建 Prompt，携带工具配置与助手级模型参数
     */
    private Prompt buildPrompt(List<Message> messages) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        if (!toolCallbacks.isEmpty()) {
            builder.toolCallbacks(toolCallbacks);
        }
        if (StringUtils.hasText(modelName)) {
            builder.model(modelName);
        }
        if (temperature != null) {
            builder.temperature(temperature);
        }
        if (maxTokens != null) {
            builder.maxTokens(maxTokens);
        }
        return new Prompt(messages, builder.build());
    }

    /**
     * 保存对话记录到上下文与实体列表
     */
    private void saveConversation(String userText, String assistantText, long startTime) {
        long costTime = System.currentTimeMillis() - startTime;
        // 维护消息上下文
        conversationHistory.add(new UserMessage(userText));
        conversationHistory.add(new AssistantMessage(assistantText));
        // 截断历史，防止无上限增长
        trimConversationHistory();

        // 构建数据库记录实体
        Record userRecord = new Record();
        userRecord.setRole(Record.ROLE_USER);
        userRecord.setMessage(userText);
        chatRecords.add(userRecord);

        Record assistantRecord = new Record();
        assistantRecord.setRole(Record.ROLE_ASSISTANT);
        assistantRecord.setMessage(assistantText);
        assistantRecord.setCostTime(costTime);
        // 检索状态随回复落库（v2.41）：query_end 帧是一次性广播，刷新页面/历史回看只剩数据库这条；
        // 未挂知识库的会话不写，否则一条 "0 篇引用" 的假状态会和"确实没查到"混为一谈
        if (!knowledgeIds.isEmpty()) {
            assistantRecord.setKnowledgebase(new Record.Knowledgebase(
                    lastKnowledgeHit.docCount(), lastKnowledgeHit.docNames(), lastKnowledgeHit.failed()));
        }
        chatRecords.add(assistantRecord);
    }

    /**
     * 加载历史聊天记录，恢复会话上下文
     * 历史记录仅注入 LLM 上下文，不写入 chatRecords（避免落库时重复插入）
     *
     * @param history 历史记录列表
     */
    public void loadChatHistory(List<Record> history) {
        if (history == null || history.isEmpty()) {
            return;
        }
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
     * 截断对话历史：仅保留最近 MAX_HISTORY_MESSAGES 条，控制注入 LLM 的上下文长度
     */
    private void trimConversationHistory() {
        while (conversationHistory.size() > MAX_HISTORY_MESSAGES) {
            conversationHistory.remove(0);
        }
    }

    /**
     * 获取本次会话新产生的聊天记录（供持久化到数据库）
     *
     * @return 新增记录列表（只读副本）
     */
    public List<Record> getNewRecords() {
        return new ArrayList<>(chatRecords);
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