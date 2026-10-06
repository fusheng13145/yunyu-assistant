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
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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

    /**
     * 失败回合落库的脱敏类别文案（v2.73 · C-143，S-22）：与 WS error 帧同一脱敏口径——
     * 异常原文可能带 Key 与内网地址，只进服务端日志，落库与外发都是这一句类别。
     */
    public static final String TURN_FAIL_REASON = "模型服务异常，本轮回复未完成";

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
        return chatStream(text, java.util.List.of());
    }

    /**
     * 多模态入口（v2.82 · C-160，收口候选 ⑪）：images 携带 base64 图片附件，
     * 经 Spring AI 的 Media 组装进 UserMessage——模型侧需支持视觉（如 qwen-vl 系列），
     * 纯文本模型会忽略或报错（上游行为）。图片不落库：records 只存文本与占位标记，
     * 图片持久化需要对象存储/BLOB 列，属真诉求再立（见 6.6 边界）。
     *
     * @param images 附件列表，元素为 {mime, dataBase64}
     */
    public Flux<Map<String, Object>> chatStream(String text, List<ChatImage> images) {
        if (!StringUtils.hasText(text)) {
            return Flux.empty();
        }
        long startTime = System.currentTimeMillis();
        String effectiveSystemPrompt = buildEffectiveSystemPrompt(text);
        Turn turn = new Turn(text, startTime);
        // 用户消息在回合开始即入上下文：工具回合的续答请求在 handleToolCalls 里组装，
        // 那一刻收尾落库还没发生，等 saveConversation 才入历史会让续答请求丢掉用户原话
        synchronized (this) {
            conversationHistory.add(buildUserMessage(text, images));
        }
        List<Message> messages = buildMessages(effectiveSystemPrompt);
        Prompt prompt = buildPrompt(messages);
        return doChatLoop(prompt, turn, 0);
    }

    /**
     * 组装多模态 UserMessage：无附件退化为纯文本（形状与既有历史一致）；
     * 附件上限（数量 4 / 单张 4MB）由 WS 入口（handleChat）先行校验，这里只做组装。
     */
    private UserMessage buildUserMessage(String text, List<ChatImage> images) {
        if (images == null || images.isEmpty()) {
            return new UserMessage(text);
        }
        List<org.springframework.ai.content.Media> media = new ArrayList<>();
        for (ChatImage image : images) {
            byte[] bytes = java.util.Base64.getDecoder().decode(image.dataBase64());
            media.add(org.springframework.ai.content.Media.builder()
                    .mimeType(org.springframework.util.MimeTypeUtils.parseMimeType(image.mime()))
                    .data(new org.springframework.core.io.ByteArrayResource(bytes))
                    .build());
        }
        return UserMessage.builder().text(text).media(media).build();
    }

    /** 多模态附件载体（WS 消息体解析后的形状） */
    public record ChatImage(String mime, String dataBase64) {
    }

    /**
     * 一次用户输入的回合级状态，跨工具迭代共享。
     * 此前保存句柄是每轮对话循环各建一份：工具回合的外层先落一次库、内层又落一次，
     * 同一轮会得到两份用户消息。收口成回合级后，onComplete / onCancel / 迭代上限三方
     * 抢的是同一个 CAS，一轮只落一次库。
     */
    private final class Turn {
        final String userText;
        final long startTime;
        /** 收尾落库时插在用户行与最终回答行之间的行：工具调用前的正文气泡与工具轨迹（role 1/2/3） */
        final List<Record> pendingRows = new ArrayList<>();
        final AtomicBoolean turnSaved = new AtomicBoolean(false);

        Turn(String userText, long startTime) {
            this.userText = userText;
            this.startTime = startTime;
        }

        /** roundText 是当前轮已生成的正文；CAS 保证一轮只落一次（onComplete 与 onCancel 竞态时的唯一裁决） */
        void saveOnce(String roundText) {
            save(roundText, null);
        }

        /** 失败回合（v2.73 · S-22）：用户的话与已生成的部分一起留痕，失败原因落脱敏类别文案 */
        void saveFailedOnce(String roundText) {
            save(roundText, TURN_FAIL_REASON);
        }

        private void save(String roundText, String failReason) {
            if (turnSaved.compareAndSet(false, true)) {
                saveConversation(userText, roundText, startTime, pendingRows, failReason);
            }
        }
    }

    /**
     * 对话主循环：真正的响应式流式处理，逐块推送文本，末尾检测工具调用。
     * turn 携带跨工具迭代的回合级状态：工具续答是同一轮用户输入的延续，落库句柄与轨迹行都归回合所有。
     */
    private Flux<Map<String, Object>> doChatLoop(Prompt prompt, Turn turn, int depth) {
        return Flux.create(sink -> {
            // StringBuffer 而非 StringBuilder：取消回调跑在"断开连接/换消息"的那个线程上，要读这里累计的正文，
            // 无锁读非线程安全缓冲区可能读到撕裂内容
            StringBuffer fullResponse = new StringBuffer();
            List<ChatResponse> allResponses = new ArrayList<>();
            // 内层订阅的唯一句柄：模型流与工具续答流先后放进同一个槽，取消时只撤当前在途的那一条；
            // 更深一层 doChatLoop 由 Flux.concat 向内传播取消，各层撤各自的内层订阅
            AtomicReference<Disposable> innerSubscription = new AtomicReference<>();
            // 回合级收尾句柄的本地别名：onComplete 与 onCancel 从同一个入口落库
            Runnable saveTurnOnce = () -> turn.saveOnce(fullResponse.toString());
            Runnable saveTurnFailedOnce = () -> turn.saveFailedOnce(fullResponse.toString());
            // 登记在途订阅：先放进槽、再复核取消位，两侧的先后关系才会覆盖全部交错顺序
            // （只复核不放进槽，或只放进槽不复核，都留得下"取消读到时还没登记、登记时又没看到取消"的漏网订阅）
            Consumer<Disposable> trackInFlight = subscription -> {
                innerSubscription.set(subscription);
                if (sink.isCancelled()) {
                    subscription.dispose();
                }
            };

            // 此前内层 subscribe 的 Disposable 被丢弃，"停止流式订阅"只撤掉了最外层：
            // 换消息或断开连接之后模型调用照常进行并计费，而这一轮的正文既不落库也无人消费
            sink.onCancel(() -> {
                Disposable inFlight = innerSubscription.getAndSet(null);
                if (inFlight != null) {
                    inFlight.dispose();
                }
                saveTurnOnce.run();
            });

            trackInFlight.accept(modelAdapter.stream(prompt).subscribe(
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
                    error -> {
                        // S-22（v2.73）：失败回合不再整轮消失——用户的话与已生成的部分照常入待落队列，
                        // fail_reason 落脱敏类别文案；异常原文只进各通道自己的日志
                        saveTurnFailedOnce.run();
                        sink.error(error);
                    },
                    () -> {                        if (allResponses.isEmpty()) {
                            sink.next(createEndChunk(fullResponse.toString(), turn.startTime));
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
                                saveTurnOnce.run();
                                sink.next(createEndChunk(fullResponse.toString(), turn.startTime));
                                sink.complete();
                                return;
                            }
                            trackInFlight.accept(handleToolCalls(assistantOutput, fullResponse.toString(), turn, depth)
                                    .subscribe(sink::next, sink::error, sink::complete));
                        } else {
                            saveTurnOnce.run();
                            sink.next(createEndChunk(fullResponse.toString(), turn.startTime));
                            sink.complete();
                        }
                    }
            ));
        });
    }

    /**
     * 处理模型发起的工具调用，执行工具并继续递归对话。
     * 框架内执行已在 buildPrompt 显式关闭，模型的 tool_calls 会真实落到这里：
     * 推帧、执行、轨迹入待落队列、带上下文续答，四件事都发生在应用侧。
     */
    private Flux<Map<String, Object>> handleToolCalls(AssistantMessage assistantMessage, String roundText,
                                                      Turn turn, int depth) {
        var toolCalls = assistantMessage.getToolCalls();

        // 检测挂断工具调用，触发挂断监听器（语音通话场景）
        if (hangupListener != null) {
            for (var tc : toolCalls) {
                if (TOOL_HANGUP.equals(tc.name())) {
                    hangupListener.accept("LLM 判定对话结束，主动挂断");
                    break;
                }
            }
        }

        // 本轮已流出的正文先入待落队列（若有）：实时里它是工具卡片之前的那个气泡，历史按同样顺序回放
        if (StringUtils.hasText(roundText)) {
            Record partialRow = new Record();
            partialRow.setRole(Record.ROLE_ASSISTANT);
            partialRow.setMessage(roundText);
            synchronized (this) {
                turn.pendingRows.add(partialRow);
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
            String resultForHistory;
            try {
                ToolCallback callback = findToolCallback(tc.name());
                if (callback == null) {
                    String errorMsg = "工具【" + tc.name() + "】不存在";
                    toolResultInfo.put("result", errorMsg);
                    toolResultInfo.put("success", false);
                    toolResultMessages.add(buildToolResponse(tc.id(), tc.name(), errorMsg));
                    resultForHistory = errorMsg;
                } else {
                    Object executeResult = callback.call(tc.arguments());
                    String resultText = extractResultText(executeResult);
                    String truncateText = truncateResult(resultText);

                    toolResultInfo.put("result", truncateText);
                    toolResultInfo.put("success", true);
                    toolResultMessages.add(buildToolResponse(tc.id(), tc.name(), resultText));
                    resultForHistory = truncateText;
                }
            } catch (ToolExecutionException e) {
                String errorMsg = "工具执行异常：" + e.getMessage();
                toolResultInfo.put("result", errorMsg);
                toolResultInfo.put("success", false);
                toolResultMessages.add(buildToolResponse(tc.id(), tc.name(), errorMsg));
                resultForHistory = errorMsg;
            } catch (Exception e) {
                String errorMsg = "工具未知异常：" + e.getMessage();
                toolResultInfo.put("result", errorMsg);
                toolResultInfo.put("success", false);
                toolResultMessages.add(buildToolResponse(tc.id(), tc.name(), errorMsg));
                resultForHistory = errorMsg;
            }
            toolResultList.add(toolResultInfo);
            // 轨迹行（S-19）：tool_args/tool_result 是 JSON 列，非 JSON 文本必须先折成合法 JSON 标量，
            // 否则 MySQL 3140 会让这一行的落库连同整个批次一起失败；message 列 NOT NULL 无默认值，
            // 轨迹行落空串（真机取证抓到的第一处：null 直接插不进库）
            Record callRow = new Record();
            callRow.setRole(Record.ROLE_TOOL_CALL);
            callRow.setMessage("");
            callRow.setToolName(tc.name());
            callRow.setToolArgs(normalizeJsonText(tc.arguments()));
            Record resultRow = new Record();
            resultRow.setRole(Record.ROLE_TOOL_RESULT);
            resultRow.setMessage("");
            resultRow.setToolName(tc.name());
            resultRow.setToolResult(toJsonScalar(resultForHistory));
            synchronized (this) {
                turn.pendingRows.add(callRow);
                turn.pendingRows.add(resultRow);
            }
        }

        // assistant(tool_calls) 与工具回执必须成对进上下文，且加锁保证取消回调插不进两次 add 之间——
        // 否则下一次请求会带着"有 tool_calls 却没有回执"的非法序列，被上游整单拒绝
        List<Message> followUpMessages;
        synchronized (this) {
            conversationHistory.add(assistantMessage);
            conversationHistory.addAll(toolResultMessages);
            trimConversationHistory();
            followUpMessages = new ArrayList<>();
            followUpMessages.add(new SystemMessage(systemPrompt));
            followUpMessages.addAll(conversationHistory);
        }

        Prompt followUpPrompt = buildPrompt(followUpMessages);

        // 流式推送：工具调用通知 -> 工具结果 -> 继续对话（深度+1）
        return Flux.concat(
                Flux.fromIterable(toolCallNotifications),
                Flux.fromIterable(toolResultList).map(this::wrapToolResultChunk),
                doChatLoop(followUpPrompt, turn, depth + 1)
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
        // 字符串型工具经 FunctionToolCallback 序列化后是 JSON 字符串标量（"文本"带引号）：
        // 解包一层，工具结果卡与轨迹行展示原文而不是带引号的序列化形状
        try {
            return objectMapper.readValue(rawStr, String.class);
        } catch (Exception ignored) {
            return rawStr;
        }
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
     * 工具参数入 JSON 列前的兜底：模型吐出的参数理应是合法 JSON，坏参折成 JSON 字符串标量，
     * 而不是让 MySQL 3140 使这一行的落库连同整个批次一起失败
     */
    private String normalizeJsonText(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            objectMapper.readTree(raw);
            return raw;
        } catch (Exception e) {
            return toJsonScalar(raw);
        }
    }

    /** 任意文本折成 JSON 字符串标量（tool_args/tool_result 是 JSON 类型列，明文直接入库会报 3140） */
    private String toJsonScalar(String text) {
        if (text == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(text);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 合并多段流式响应：带 tool_calls 的那块可能不是最后一块（末块常是纯用量统计块，result 为空），
     * 从尾向前找第一个带 tool_calls 的响应，找不到再回退到最后一个带 result 的响应
     */
    private ChatResponse mergeResponses(List<ChatResponse> responses) {
        if (responses.isEmpty()) {
            return null;
        }
        for (int i = responses.size() - 1; i >= 0; i--) {
            ChatResponse response = responses.get(i);
            if (response != null && response.getResult() != null && response.getResult().getOutput() != null
                    && !response.getResult().getOutput().getToolCalls().isEmpty()) {
                return response;
            }
        }
        for (int i = responses.size() - 1; i >= 0; i--) {
            ChatResponse response = responses.get(i);
            if (response != null && response.getResult() != null) {
                return response;
            }
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
     * 组装消息列表：系统提示 + 历史对话（当前用户消息已在回合开始入列）
     */
    private List<Message> buildMessages(String systemPromptText) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPromptText));
        List<Message> historyCopy;
        synchronized (this) {
            historyCopy = new ArrayList<>(conversationHistory);
        }
        messages.addAll(historyCopy);
        return messages;
    }

    /**
     * 构建 Prompt，携带工具配置与助手级模型参数
     */
    private Prompt buildPrompt(List<Message> messages) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        if (!toolCallbacks.isEmpty()) {
            builder.toolCallbacks(toolCallbacks);
            // S-24：显式接管工具执行。Spring AI 默认在框架内部执行工具并吞掉 tool_call 响应，
            // 那条路径里工具照跑，但 tool_call/tool_result 帧不会外发、hangup 监听不可达、轨迹无从落库
            builder.internalToolExecutionEnabled(false);
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
     * 保存对话记录到上下文与实体列表。
     * pendingRows 携带本回合已产生的行（工具前正文、工具轨迹），顺序插在用户行与回答行之间，
     * 历史回看的展示顺序与实时帧一致（S-19）。failReason 非空＝失败回合（S-22），落类别文案。
     */
    private synchronized void saveConversation(String userText, String assistantText, long startTime,
                                                List<Record> pendingRows, String failReason) {
        long costTime = System.currentTimeMillis() - startTime;
        // 维护消息上下文（用户消息已在回合开始入列，这里只补助手回复）
        conversationHistory.add(new AssistantMessage(assistantText));
        // 截断历史，防止无上限增长
        trimConversationHistory();

        // 构建数据库记录实体
        Record userRecord = new Record();
        userRecord.setRole(Record.ROLE_USER);
        userRecord.setMessage(userText);
        chatRecords.add(userRecord);

        chatRecords.addAll(pendingRows);

        Record assistantRecord = new Record();
        assistantRecord.setRole(Record.ROLE_ASSISTANT);
        assistantRecord.setMessage(assistantText);
        if (StringUtils.hasText(failReason)) {
            assistantRecord.setFailReason(failReason);
        }
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
                // 工具轨迹行（role 2/3）与未知角色都不回灌上下文：回灌要合成 tool_call id 并重放截断回执，
                // 属另一条口径；直接折成 ToolResponseMessage 则是对不上任何 tool_call 的悬空回执
                log.warn("会话历史存在不注入上下文的角色，跳过，recordId:{}，role:{}", record.getId(), record.getRole());
            }
        }
    }

    /**
     * 截断对话历史：仅保留最近 MAX_HISTORY_MESSAGES 条，控制注入 LLM 的上下文长度。
     * 截断只许落在回合边界：把一组 [assistant(tool_calls), tool回执] 从中间切开，
     * 下一次请求就会以悬空回执或未获回执的 tool_calls 开头，被上游整单拒绝。
     */
    void trimConversationHistory() {
        while (conversationHistory.size() > MAX_HISTORY_MESSAGES) {
            conversationHistory.remove(0);
        }
        dropHeadToolGroupFragments(conversationHistory);
    }

    /** 丢掉头部"被切开工具组"的一半：悬空的工具回执，或未获回执的 tool_calls */
    static void dropHeadToolGroupFragments(List<Message> history) {
        while (!history.isEmpty() && headSplitsToolGroup(history.get(0))) {
            history.remove(0);
        }
    }

    /** 头部消息是否是"被切开工具组"的一半：悬空的工具回执，或未获回执的 tool_calls */
    static boolean headSplitsToolGroup(Message message) {
        if (message instanceof ToolResponseMessage) {
            return true;
        }
        return message instanceof AssistantMessage assistantMessage && !assistantMessage.getToolCalls().isEmpty();
    }

    /**
     * 取出并清空本次会话待落库的聊天记录。
     *
     * 取出式而非只读：逐轮落库与断开兜底共用同一个待落队列，若不清空则同一轮消息会被落两次。
     * 与 {@link #saveConversation} 同锁，因为落库发生在流线程、取用可能在容器线程。
     *
     * @return 待落库记录；无待落记录时返回空列表
     */
    public synchronized List<Record> drainPendingRecords() {
        if (chatRecords.isEmpty()) {
            return List.of();
        }
        List<Record> pending = new ArrayList<>(chatRecords);
        chatRecords.clear();
        return pending;
    }

    /**
     * 修改人设提示词，同时清空会话历史
     *
     * @param prompt 新的系统提示词
     */
    public synchronized void changePrompt(String prompt) {
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
    public synchronized void reset() {
        this.conversationHistory.clear();
        this.chatRecords.clear();
    }

    /**
     * 关闭会话，导出会话状态用于持久化
     *
     * @return 会话状态：聊天记录、人设、知识库ID
     */
    public synchronized Map<String, String> close() {
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