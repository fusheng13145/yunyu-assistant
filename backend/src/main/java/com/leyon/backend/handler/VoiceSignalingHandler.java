package com.leyon.backend.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.CallRecord;
import com.leyon.backend.entity.Record;
import com.leyon.backend.service.AssistantService;
import com.leyon.backend.service.CallRecordService;
import com.leyon.backend.service.ChatService;
import com.leyon.backend.service.KnowledgeProvider;
import com.leyon.backend.service.ModelAdapter;
import com.leyon.backend.service.RecordService;
import com.leyon.backend.service.RustPBXService;
import com.leyon.backend.tool.ToolRegistry;
import com.leyon.backend.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.Disposable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语音信令 WebSocket 处理器
 * 负责 WebRTC 信令交互、语音通话、ASR 语音识别、AI 对话、TTS 语音合成全链路处理
 *
 * @author leyon
 */
@Component
public class VoiceSignalingHandler extends TextWebSocketHandler {

    // 协议版本
    /** 当前协议版本 */
    private static final String PROTOCOL_VERSION = "1.0.0";

    // 常量定义
    /** 消息类型（服务端→客户端，与 PRD 6.7 及前端监听类型严格对齐） */
    private static final String MSG_TYPE_CONNECTED = "connected";
    private static final String MSG_TYPE_OFFER = "offer";
    private static final String MSG_TYPE_WEBRTC_ANSWER = "webrtc_answer";
    private static final String MSG_TYPE_WEBRTC_CONNECTED = "webrtc_connected";
    private static final String MSG_TYPE_ASR_DELTA = "asr_delta";
    private static final String MSG_TYPE_ASSISTANT_MSG = "assistant_message";
    private static final String MSG_TYPE_QUERY_END = "query_end";
    private static final String MSG_TYPE_HANGUP = "hangup";
    private static final String MSG_TYPE_ERROR = "error";
    private static final String MSG_TYPE_AUTH = "auth";
    private static final String MSG_TYPE_PING = "ping";
    private static final String MSG_TYPE_PONG = "pong";

    /** 字段名 */
    private static final String FIELD_SDP = "sdp";
    private static final String FIELD_ASSISTANT_ID = "assistantId";
    private static final String FIELD_GREETING = "greeting";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_SEGMENT = "segment";
    private static final String FIELD_STREAM_END = "streamEnd";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_MESSAGE = "message";
    private static final String FIELD_COST_TIME = "costTime";
    private static final String FIELD_KNOWLEDGEBASE = "knowledgebase";

    /** 会话属性Key */
    private static final String SESSION_ATTR_USER_ID = "userId";

    // ====================== 日志 ======================
    private final Logger logger = LoggerFactory.getLogger(VoiceSignalingHandler.class);

    // 依赖注入（使用扩展抽象层接口，与 ChatWebSocketHandler 保持一致）
    private final RustPBXService rustPBXService;
    private final AssistantService assistantService;
    private final ModelAdapter modelAdapter;
    private final KnowledgeProvider knowledgeProvider;
    private final RecordService recordService;
    private final CallRecordService callRecordService;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final JwtUtil jwtUtil;

    // 会话缓存
    /** 会话ID -> 语音网关会话ID */
    private final ConcurrentHashMap<String, String> sessionRustpbxMap = new ConcurrentHashMap<>();
    /** 会话ID -> 聊天服务实例 */
    private final ConcurrentHashMap<String, ChatService> sessionChatServiceMap = new ConcurrentHashMap<>();
    /** 会话ID -> 助手ID（用于落库归属） */
    private final ConcurrentHashMap<String, String> sessionAssistantMap = new ConcurrentHashMap<>();
    /** 会话ID -> 助手音色（用于 TTS 播报） */
    private final ConcurrentHashMap<String, String> sessionVoiceMap = new ConcurrentHashMap<>();
    /** 会话ID -> 通话记录ID（用于通话记录统计） */
    private final ConcurrentHashMap<String, String> sessionCallRecordMap = new ConcurrentHashMap<>();
    /** 会话ID -> 流式订阅器，防止并发流堆积 */
    private final ConcurrentHashMap<String, Disposable> activeSubscriptions = new ConcurrentHashMap<>();
    /** 已认证的会话ID集合 */
    private final ConcurrentHashMap<String, Boolean> authenticatedSessions = new ConcurrentHashMap<>();

    public VoiceSignalingHandler(RustPBXService rustPBXService,
                                 AssistantService assistantService,
                                 ModelAdapter modelAdapter,
                                 KnowledgeProvider knowledgeProvider,
                                 RecordService recordService,
                                 CallRecordService callRecordService,
                                 ObjectMapper objectMapper,
                                 ToolRegistry toolRegistry,
                                 JwtUtil jwtUtil) {
        this.rustPBXService = rustPBXService;
        this.assistantService = assistantService;
        this.modelAdapter = modelAdapter;
        this.knowledgeProvider = knowledgeProvider;
        this.recordService = recordService;
        this.callRecordService = callRecordService;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.jwtUtil = jwtUtil;
    }

    // 连接建立
    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        String sessionId = session.getId();
        // 安全改进：不再直接发送 connected，等待首条 auth 消息认证
        String userId = (String) session.getAttributes().get(SESSION_ATTR_USER_ID);
        if (userId != null && !userId.isBlank()) {
            // 握手阶段已通过拦截器认证，直接标记为已认证
            authenticatedSessions.put(sessionId, true);
            sendMessage(session, MSG_TYPE_CONNECTED, null);
            logger.info("语音信令连接建立（握手阶段已认证），会话ID:{}", sessionId);
        } else {
            // 等待客户端发送 auth 消息
            logger.info("语音信令连接已建立，等待认证消息，会话ID:{}", sessionId);
        }
    }

    // 接收客户端消息
    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        String sessionId = session.getId();

        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.get(FIELD_TYPE).asText();

            // 处理认证消息（在业务逻辑之前）
            if (MSG_TYPE_AUTH.equals(type)) {
                handleAuth(session, sessionId, node);
                return;
            }

            // 非认证消息需要已认证状态
            if (!authenticatedSessions.containsKey(sessionId)) {
                sendMessage(session, MSG_TYPE_ERROR, "未认证，请先发送认证消息");
                return;
            }

            dispatchMessage(session, type, node);
        } catch (Exception e) {
            logger.error("解析/处理消息异常，会话ID:{}", sessionId, e);
            // 安全改进：异常信息脱敏，不泄露内部细节
            sendMessage(session, MSG_TYPE_ERROR, "消息处理失败，请重试");
        }
    }

    /**
     * 处理认证消息
     */
    private void handleAuth(WebSocketSession session, String sessionId, JsonNode node) {
        if (authenticatedSessions.containsKey(sessionId)) {
            return; // 已认证则忽略
        }

        String token = node.path("token").asText("");
        if (token.isBlank()) {
            sendMessage(session, MSG_TYPE_ERROR, "认证失败：缺少 Token");
            closeSession(session);
            return;
        }

        // 使用 JwtUtil 进行严格的 Token 验证（与 ChatWebSocketHandler 保持一致）
        if (!jwtUtil.validateToken(token)) {
            sendMessage(session, MSG_TYPE_ERROR, "认证失败：无效的 Token");
            closeSession(session);
            return;
        }

        String userId = jwtUtil.getUserIdFromToken(token);
        session.getAttributes().put(SESSION_ATTR_USER_ID, userId);
        authenticatedSessions.put(sessionId, true);
        sendMessage(session, MSG_TYPE_CONNECTED, null);
        logger.info("语音信令认证成功，会话ID:{}，用户ID:{}", sessionId, userId);
    }

    /**
     * 消息统一分发
     */
    private void dispatchMessage(WebSocketSession session, String type, JsonNode node) {
        switch (type) {
            case MSG_TYPE_OFFER -> handleOffer(session, node);
            case MSG_TYPE_WEBRTC_CONNECTED -> handleWebRtcConnected(session, node);
            case MSG_TYPE_HANGUP -> handleHangup(session);
            case MSG_TYPE_PING -> sendMessage(session, MSG_TYPE_PONG, null);
            default -> sendMessage(session, MSG_TYPE_ERROR, "未知消息类型");
        }
    }

    // 信令处理器
    /**
     * 处理 WebRTC Offer 信令，对接语音网关
     */
    private void handleOffer(@NonNull WebSocketSession session, @NonNull JsonNode node) {
        String sessionId = session.getId();
        String offerSDP = node.get(FIELD_SDP).asText();
        String assistantId = node.has(FIELD_ASSISTANT_ID) ? node.get(FIELD_ASSISTANT_ID).asText() : null;

        ChatService chatService = null;
        if (assistantId != null) {
            chatService = initChatService(session, assistantId);
            if (chatService == null) {
                return;
            }
            sessionChatServiceMap.put(sessionId, chatService);
            sessionAssistantMap.put(sessionId, assistantId);
        }

        String voice = sessionVoiceMap.get(sessionId);

        try {
            // 连接语音网关，注册回调
            String rustSessionId = rustPBXService.connectToRustPBX(
                    offerSDP,
                    assistantId,
                    voice,
                    // answer 回调：data 直接为 SDP 字符串（前端 webrtc.handleAnswer(data.data)）
                    answer -> sendMessage(session, MSG_TYPE_WEBRTC_ANSWER, answer),
                    asrText -> handleAsrResult(session, asrText),
                    // 沉默追问回调：用户超时未说话时播报追问
                    promptText -> handleSilencePrompt(session, promptText)
            );
            sessionRustpbxMap.put(sessionId, rustSessionId);
            logger.info("语音网关连接成功，会话ID:{}，网关会话ID:{}", sessionId, rustSessionId);
        } catch (Exception e) {
            logger.error("连接语音网关失败，会话ID:{}", sessionId, e);
            // 安全改进：异常信息脱敏
            sendMessage(session, MSG_TYPE_ERROR, "语音服务连接失败，请稍后重试");
        }
    }

    /**
     * 沉默追问：用户超时未说话，播报追问文案（F3.5）
     */
    private void handleSilencePrompt(WebSocketSession session, String promptText) {
        String sessionId = session.getId();
        String rustpbxSessionId = sessionRustpbxMap.get(sessionId);
        if (rustpbxSessionId != null && StringUtils.hasText(promptText)) {
            logger.info("沉默追问触发，会话ID:{}，文案:{}", sessionId, promptText);
            rustPBXService.sendTTS(rustpbxSessionId, promptText, sessionVoiceMap.get(sessionId));
        }
    }

    /**
     * 初始化 ChatService 并加载历史记录、知识库关联、权限校验
     */
    private ChatService initChatService(WebSocketSession session, String assistantId) {
        String sessionId = session.getId();
        Assistant assistant = assistantService.getById(assistantId);
        if (assistant == null) {
            sendMessage(session, MSG_TYPE_ERROR, "助手不存在");
            return null;
        }

        // 权限校验
        String userId = (String) session.getAttributes().get(SESSION_ATTR_USER_ID);
        if (userId == null || !userId.equals(assistant.getUserId())) {
            sendMessage(session, MSG_TYPE_ERROR, "无权访问此助手");
            return null;
        }

        // 缓存助手音色（TTS 播报使用）
        String voice = assistant.getVoice();
        if (StringUtils.hasText(voice)) {
            sessionVoiceMap.put(sessionId, voice);
        }

        // 从服务端持久化的 knowledge_ids 加载知识库关联
        List<String> knowledgeIds = parseKnowledgeIds(assistant.getKnowledgeIds());

        // 初始化对话服务（使用新的抽象接口依赖）
        ChatService chatService = new ChatService(
                modelAdapter, knowledgeProvider, objectMapper,
                assistant.getPersonality(), knowledgeIds, toolRegistry.getAllToolCallbacks()
        );
        // 注册挂断监听器：LLM 调用 hangup 工具时主动挂断通话
        chatService.setHangupListener(reason -> handleLlmHangup(session, reason));
        // 应用助手级模型参数（覆盖全局默认）
        chatService.setModelParams(assistant.getModelName(), assistant.getTemperature(), assistant.getMaxTokens());

        // 加载历史聊天记录（性能优化：仅加载最近50条）
        try {
            List<Record> history = recordService.listByAssistantIdLimit(assistantId, 50);
            if (!history.isEmpty()) {
                chatService.loadChatHistory(history);
            }
        } catch (Exception e) {
            logger.error("加载聊天历史记录失败，助手ID:{}", assistantId, e);
        }
        return chatService;
    }

    /**
     * 解析助手 knowledge_ids 字段（JSON 数组字符串）为 ID 列表
     */
    private List<String> parseKnowledgeIds(String knowledgeIdsJson) {
        if (!StringUtils.hasText(knowledgeIdsJson)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(knowledgeIdsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            logger.warn("解析助手 knowledge_ids 失败: {}", knowledgeIdsJson);
            return new ArrayList<>();
        }
    }

    /**
     * WebRTC 通话通道建立完成
     */
    private void handleWebRtcConnected(WebSocketSession session, JsonNode node) {
        String greeting = node.has(FIELD_GREETING) ? node.get(FIELD_GREETING).asText()
                : "你好，我是云谕助手，有什么可以帮助你的吗？";
        String sessionId = session.getId();
        String rustpbxSessionId = sessionRustpbxMap.get(sessionId);

        if (rustpbxSessionId != null) {
            rustPBXService.sendTTS(rustpbxSessionId, greeting, sessionVoiceMap.get(sessionId));
        }
        // 通话真正建立，创建通话记录；下发 callId 供前端录音结束后回传上传
        String callId = createCallRecord(session);
        Map<String, Object> data = new HashMap<>();
        if (StringUtils.hasText(callId)) {
            data.put("callId", callId);
        }
        sendMessage(session, MSG_TYPE_WEBRTC_CONNECTED, data);
    }

    /**
     * 创建通话记录（状态=进行中），返回通话记录ID
     */
    private String createCallRecord(WebSocketSession session) {
        String sessionId = session.getId();
        String assistantId = sessionAssistantMap.get(sessionId);
        String userId = (String) session.getAttributes().get(SESSION_ATTR_USER_ID);
        if (!StringUtils.hasText(assistantId) || !StringUtils.hasText(userId)) {
            return null;
        }
        // 避免重复创建
        if (sessionCallRecordMap.containsKey(sessionId)) {
            return sessionCallRecordMap.get(sessionId);
        }
        try {
            CallRecord record = new CallRecord();
            record.setUserId(userId);
            record.setAssistantId(assistantId);
            record.setStatus(CallRecord.STATUS_IN_PROGRESS);
            record.setDurationSec(0);
            record.setMessageCount(0);
            record.setStartedAt(LocalDateTime.now());
            record.setIsDeleted(CallRecord.NOT_DELETED);
            callRecordService.create(record);
            sessionCallRecordMap.put(sessionId, record.getId());
            logger.info("通话记录已创建，会话ID:{}，通话ID:{}", sessionId, record.getId());
            return record.getId();
        } catch (Exception e) {
            logger.error("创建通话记录失败，会话ID:{}", sessionId, e);
            return null;
        }
    }

    /**
     * 结束通话记录（更新状态/时长/消息数）
     */
    private void finishCallRecord(String sessionId, int status, int messageCount) {
        String callId = sessionCallRecordMap.remove(sessionId);
        if (!StringUtils.hasText(callId)) {
            return;
        }
        try {
            CallRecord record = callRecordService.getById(callId);
            if (record == null) {
                return;
            }
            record.setStatus(status);
            record.setEndedAt(LocalDateTime.now());
            record.setMessageCount(messageCount);
            if (record.getStartedAt() != null) {
                long seconds = Duration.between(record.getStartedAt(), record.getEndedAt()).getSeconds();
                record.setDurationSec((int) Math.max(seconds, 0));
            }
            callRecordService.update(record);
            logger.info("通话记录已更新，通话ID:{}，状态:{}，消息数:{}", callId, status, messageCount);
        } catch (Exception e) {
            logger.error("更新通话记录失败，通话ID:{}", callId, e);
        }
    }

    /**
     * 处理语音识别结果 ASR
     */
    private void handleAsrResult(WebSocketSession session, String asrText) {
        if (asrText == null || asrText.isBlank()) {
            return;
        }
        String sessionId = session.getId();
        logger.info("收到语音识别文本，会话ID:{}，内容:{}", sessionId, asrText);

        // 回显识别文本到前端（asr_delta 实时转写）
        sendMessage(session, MSG_TYPE_ASR_DELTA, Map.of(FIELD_TEXT, asrText));

        ChatService chatService = sessionChatServiceMap.get(sessionId);
        String rustpbxSessionId = sessionRustpbxMap.get(sessionId);
        if (chatService == null || rustpbxSessionId == null) {
            sendMessage(session, MSG_TYPE_QUERY_END,
                    Map.of(FIELD_MESSAGE, "抱歉，语音助手未就绪，请稍后重试。", "status", "error"));
            return;
        }

        // 终止上一轮未结束的流式请求
        Disposable oldSub = activeSubscriptions.get(sessionId);
        if (oldSub != null && !oldSub.isDisposed()) {
            oldSub.dispose();
        }

        String voice = sessionVoiceMap.get(sessionId);
        StringBuilder replyBuilder = new StringBuilder();
        // 捕获流结束帧中的 message/costTime/knowledgebase，用于 query_end 推送
        final Map<String, Object> endInfo = new HashMap<>();

        Disposable subscription = chatService.chatStream(asrText)
                .subscribe(
                        chunk -> {
                            // 工具调用/结果直接透传
                            if (chunk.containsKey(FIELD_TYPE)) {
                                sendRawMessage(session, chunk);
                                return;
                            }
                            Object streamEnd = chunk.get(FIELD_STREAM_END);
                            if (Boolean.TRUE.equals(streamEnd)) {
                                // 捕获结束信息
                                if (chunk.get(FIELD_MESSAGE) != null) endInfo.put(FIELD_MESSAGE, chunk.get(FIELD_MESSAGE));
                                if (chunk.get(FIELD_COST_TIME) != null) endInfo.put(FIELD_COST_TIME, chunk.get(FIELD_COST_TIME));
                                if (chunk.get(FIELD_KNOWLEDGEBASE) != null) endInfo.put(FIELD_KNOWLEDGEBASE, chunk.get(FIELD_KNOWLEDGEBASE));
                                sendMessage(session, MSG_TYPE_ASSISTANT_MSG, chunk);
                            } else {
                                // 流式分段下发（assistant_message）
                                sendMessage(session, MSG_TYPE_ASSISTANT_MSG, chunk);
                                // 拼接完整回复
                                Object segment = chunk.get(FIELD_SEGMENT);
                                if (segment instanceof String str && !str.isBlank()) {
                                    replyBuilder.append(str);
                                }
                            }
                        },
                        error -> {
                            logger.error("AI 语音对话异常，会话ID:{}", sessionId, error);
                            String errorMsg = "抱歉，我遇到了一些问题。";
                            sendMessage(session, MSG_TYPE_QUERY_END,
                                    Map.of(FIELD_MESSAGE, errorMsg, "status", "error"));
                            rustPBXService.sendTTS(rustpbxSessionId, errorMsg, voice);
                            activeSubscriptions.remove(sessionId);
                        },
                        () -> {
                            // 流结束：执行 TTS 完整播报 + 推送 query_end
                            String fullReply = replyBuilder.toString();
                            if (!StringUtils.hasText(fullReply)) {
                                fullReply = String.valueOf(endInfo.getOrDefault(FIELD_MESSAGE, ""));
                            }
                            onVoiceResponseComplete(session, fullReply, endInfo);
                            activeSubscriptions.remove(sessionId);
                        }
                );

        activeSubscriptions.put(sessionId, subscription);
    }

    /**
     * AI 完整回复生成完毕：执行 TTS 语音播放 + 推送 query_end（含耗时与知识库引用）
     */
    public void onVoiceResponseComplete(WebSocketSession session, String aiReply, Map<String, Object> endInfo) {
        String sessionId = session.getId();
        String rustpbxSessionId = sessionRustpbxMap.get(sessionId);
        if (rustpbxSessionId != null && aiReply != null && !aiReply.isBlank()) {
            rustPBXService.sendTTS(rustpbxSessionId, aiReply, sessionVoiceMap.get(sessionId));
        }

        // 推送 query_end（对齐前端 finishStreamMessage 期望结构）
        Map<String, Object> queryEndData = new HashMap<>();
        queryEndData.put(FIELD_MESSAGE, aiReply);
        queryEndData.put(FIELD_COST_TIME, endInfo.getOrDefault(FIELD_COST_TIME, 0L));
        queryEndData.put(FIELD_KNOWLEDGEBASE, endInfo.getOrDefault(FIELD_KNOWLEDGEBASE,
                Map.of("docCount", 0, "docName", List.of())));
        sendMessage(session, MSG_TYPE_QUERY_END, queryEndData);
    }

    /**
     * 挂断通话（客户端主动）
     */
    private void handleHangup(WebSocketSession session) {
        String sessionId = session.getId();
        releaseSessionResource(sessionId);
        sendMessage(session, MSG_TYPE_HANGUP, null);
    }

    /**
     * LLM 判定对话结束，主动挂断（F3.6）
     */
    private void handleLlmHangup(WebSocketSession session, String reason) {
        String sessionId = session.getId();
        logger.info("LLM 主动挂断，会话ID:{}，原因:{}", sessionId, reason);
        // 通知前端挂断
        sendMessage(session, MSG_TYPE_HANGUP, null);
        // 释放资源：停止流式订阅、断网关、落库
        releaseSessionResource(sessionId);
        // 关闭 WebSocket 会话
        closeSession(session);
    }

    // 连接关闭
    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        String sessionId = session.getId();
        logger.info("语音信令连接断开，会话ID:{}，关闭状态:{}", sessionId, status);
        // 区分正常挂断与异常中断：非正常关闭码标记为中断
        int endStatus = isNormalClose(status) ? CallRecord.STATUS_ENDED : CallRecord.STATUS_INTERRUPTED;
        releaseSessionResource(sessionId, endStatus);
        authenticatedSessions.remove(sessionId);
    }

    /**
     * 判断关闭状态是否为正常关闭（正常关闭码 1000/1001）
     */
    private boolean isNormalClose(CloseStatus status) {
        if (status == null) {
            return false;
        }
        int code = status.getCode();
        return code == CloseStatus.NORMAL.getCode() || code == CloseStatus.GOING_AWAY.getCode();
    }

    /**
     * 统一释放会话资源（默认正常结束）
     */
    private void releaseSessionResource(String sessionId) {
        releaseSessionResource(sessionId, CallRecord.STATUS_ENDED);
    }

    /**
     * 统一释放会话资源
     *
     * @param sessionId 会话ID
     * @param endStatus 通话结束状态（正常结束 / 中断）
     */
    private void releaseSessionResource(String sessionId, int endStatus) {
        // 停止流式订阅
        Disposable subscription = activeSubscriptions.remove(sessionId);
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
        // 断开语音网关连接
        String rustpbxSessionId = sessionRustpbxMap.remove(sessionId);
        if (rustpbxSessionId != null) {
            try {
                rustPBXService.disconnect(rustpbxSessionId);
            } catch (Exception e) {
                logger.error("断开语音网关连接失败，网关会话ID:{}", rustpbxSessionId, e);
            }
        }
        // 持久化本次通话新增的对话记录（修复语音消息不落库的问题）
        String assistantId = sessionAssistantMap.remove(sessionId);
        ChatService chatService = sessionChatServiceMap.remove(sessionId);
        int messageCount = 0;
        if (assistantId != null && chatService != null) {
            messageCount = persistNewRecords(sessionId, assistantId, chatService);
        }
        // 结束通话记录
        finishCallRecord(sessionId, endStatus, messageCount);
        sessionVoiceMap.remove(sessionId);
    }

    /**
     * 批量落库本次会话新增的对话记录
     *
     * @param sessionId   会话ID（用于关联通话记录）
     * @param assistantId 助手ID
     * @param chatService 聊天服务实例
     * @return 落库的消息数
     */
    private int persistNewRecords(String sessionId, String assistantId, ChatService chatService) {
        List<Record> newRecords = chatService.getNewRecords();
        if (newRecords == null || newRecords.isEmpty()) {
            return 0;
        }
        String callId = sessionCallRecordMap.get(sessionId);
        int saved = 0;
        for (Record record : newRecords) {
            if (!StringUtils.hasText(record.getMessage())) {
                continue;
            }
            record.setId(null); // 由 MyBatis-Plus 自动生成 UUID
            record.setAssistantId(assistantId);
            if (StringUtils.hasText(callId)) {
                record.setCallId(callId);
            }
            record.setIsDeleted(Record.NOT_DELETED);
            try {
                recordService.add(record);
                saved++;
            } catch (Exception e) {
                logger.error("保存语音对话记录失败，助手ID:{}，role:{}", assistantId, record.getRole(), e);
            }
        }
        if (saved > 0) {
            logger.info("已持久化 {} 条语音对话记录，助手ID:{}", saved, assistantId);
        }
        return saved;
    }

    // 消息发送工具方法
    /**
     * 标准消息发送（包装 type + data + protocolVersion）
     */
    private void sendMessage(WebSocketSession session, String type, Object data) {
        if (!session.isOpen()) {
            return;
        }
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put(FIELD_TYPE, type);
            msg.put("protocolVersion", PROTOCOL_VERSION);
            if (data != null) {
                msg.put("data", data);
            }
            String json = objectMapper.writeValueAsString(msg);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            logger.error("发送标准消息失败", e);
        }
    }

    /**
     * 发送原始 JSON 消息
     */
    private void sendRawMessage(WebSocketSession session, Map<String, Object> message) {
        if (!session.isOpen()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(message);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            logger.error("发送原始消息失败", e);
        }
    }

    /**
     * 关闭指定会话
     */
    private void closeSession(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close();
            }
        } catch (Exception e) {
            logger.error("关闭 WebSocket 会话异常", e);
        }
    }
}
