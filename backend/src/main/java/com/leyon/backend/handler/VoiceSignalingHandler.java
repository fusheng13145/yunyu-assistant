package com.leyon.backend.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.Record;
import com.leyon.backend.service.AssistantService;
import com.leyon.backend.service.ChatService;
import com.leyon.backend.service.KnowledgeService;
import com.leyon.backend.service.RecordService;
import com.leyon.backend.service.RustPBXService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.Disposable;

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

    // 常量定义
    /** 消息类型 */
    private static final String MSG_TYPE_CONNECTED = "connected";
    private static final String MSG_TYPE_OFFER = "offer";
    private static final String MSG_TYPE_ANSWER = "answer";
    private static final String MSG_TYPE_WEBRTC_CONNECTED = "webrtc_connected";
    private static final String MSG_TYPE_HANGUP = "hangup";
    private static final String MSG_TYPE_ERROR = "error";
    private static final String MSG_TYPE_ASR_TEXT = "asr_text";
    private static final String MSG_TYPE_VOICE_CHUNK = "voice_chunk";
    private static final String MSG_TYPE_VOICE_RESPONSE = "voice_response";

    /** 字段名 */
    private static final String FIELD_SDP = "sdp";
    private static final String FIELD_ASSISTANT_ID = "assistantId";
    private static final String FIELD_GREETING = "greeting";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_SEGMENT = "segment";

    /** TTS 音色 */
    private static final String TTS_VOICE = "longxiaochun";
    /** 会话属性Key */
    private static final String SESSION_ATTR_USER_ID = "userId";

    // ====================== 日志 ======================
    private final Logger logger = LoggerFactory.getLogger(VoiceSignalingHandler.class);

    // 依赖注入
    private final RustPBXService rustPBXService;
    private final AssistantService assistantService;
    private final ChatModel chatModel;
    private final KnowledgeService knowledgeService;
    private final RecordService recordService;
    private final ObjectMapper objectMapper;
    private final List<ToolCallback> toolCallbacks;

    // 会话缓存
    /** 会话ID -> 语音网关会话ID */
    private final ConcurrentHashMap<String, String> sessionRustpbxMap = new ConcurrentHashMap<>();
    /** 会话ID -> 聊天服务实例 */
    private final ConcurrentHashMap<String, ChatService> sessionChatServiceMap = new ConcurrentHashMap<>();
    /** 会话ID -> 流式订阅器，防止并发流堆积 */
    private final ConcurrentHashMap<String, Disposable> activeSubscriptions = new ConcurrentHashMap<>();

    public VoiceSignalingHandler(RustPBXService rustPBXService,
                                 AssistantService assistantService,
                                 ChatModel chatModel,
                                 KnowledgeService knowledgeService,
                                 RecordService recordService,
                                 ObjectMapper objectMapper,
                                 List<ToolCallback> toolCallbacks) {
        this.rustPBXService = rustPBXService;
        this.assistantService = assistantService;
        this.chatModel = chatModel;
        this.knowledgeService = knowledgeService;
        this.recordService = recordService;
        this.objectMapper = objectMapper;
        this.toolCallbacks = toolCallbacks == null ? List.of() : toolCallbacks;
    }

    // 连接建立
    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        String sessionId = session.getId();
        logger.info("语音信令连接建立，会话ID:{}", sessionId);
        sendMessage(session, MSG_TYPE_CONNECTED, null);
    }

    // 接收客户端消息
    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        String sessionId = session.getId();
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.get(FIELD_TYPE).asText();
            dispatchMessage(session, type, node);
        } catch (Exception e) {
            logger.error("解析/处理消息异常，会话ID:{}", sessionId, e);
            sendMessage(session, MSG_TYPE_ERROR, "Failed to process message: " + e.getMessage());
        }
    }

    /**
     * 消息统一分发
     */
    private void dispatchMessage(WebSocketSession session, String type, JsonNode node) {
        switch (type) {
            case MSG_TYPE_OFFER -> handleOffer(session, node);
            case MSG_TYPE_WEBRTC_CONNECTED -> handleWebRtcConnected(session, node);
            case MSG_TYPE_HANGUP -> handleHangup(session);
            default -> sendMessage(session, MSG_TYPE_ERROR, "Unknown message type: " + type);
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
        }

        try {
            // 连接语音网关，注册回调
            String rustSessionId = rustPBXService.connectToRustPBX(
                    offerSDP,
                    assistantId,
                    answer -> sendMessage(session, MSG_TYPE_ANSWER, Map.of(FIELD_SDP, answer)),
                    asrText -> handleAsrResult(session, asrText)
            );
            sessionRustpbxMap.put(sessionId, rustSessionId);
            logger.info("语音网关连接成功，会话ID:{}，网关会话ID:{}", sessionId, rustSessionId);
        } catch (Exception e) {
            logger.error("连接语音网关失败，会话ID:{}", sessionId, e);
            sendMessage(session, MSG_TYPE_ERROR, "Failed to connect to voice server: " + e.getMessage());
        }
    }

    /**
     * 初始化 ChatService 并加载历史记录、权限校验
     */
    private ChatService initChatService(WebSocketSession session, String assistantId) {
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

        // 初始化对话服务
        List<String> knowledgeIds = new ArrayList<>();
        ChatService chatService = new ChatService(
                chatModel, knowledgeService, objectMapper,
                assistant.getPersonality(), knowledgeIds, toolCallbacks
        );

        // 加载历史聊天记录
        try {
            List<Record> history = recordService.listByAssistantId(assistantId);
            if (!history.isEmpty()) {
                chatService.loadChatHistory(history);
            }
        } catch (Exception e) {
            logger.error("加载聊天历史记录失败，助手ID:{}", assistantId, e);
        }
        return chatService;
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
            rustPBXService.sendTTS(rustpbxSessionId, greeting, TTS_VOICE);
        }
        sendMessage(session, MSG_TYPE_WEBRTC_CONNECTED, null);
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

        // 回显识别文本到前端
        sendMessage(session, MSG_TYPE_ASR_TEXT, Map.of("text", asrText));

        ChatService chatService = sessionChatServiceMap.get(sessionId);
        String rustpbxSessionId = sessionRustpbxMap.get(sessionId);
        if (chatService == null || rustpbxSessionId == null) {
            sendMessage(session, MSG_TYPE_VOICE_RESPONSE,
                    Map.of("text", "抱歉，语音助手未就绪，请稍后重试。", "status", "error"));
            return;
        }

        // 终止上一轮未结束的流式请求
        Disposable oldSub = activeSubscriptions.get(sessionId);
        if (oldSub != null && !oldSub.isDisposed()) {
            oldSub.dispose();
        }

        StringBuilder replyBuilder = new StringBuilder();
        Disposable subscription = chatService.chatStream(asrText)
                .subscribe(
                        chunk -> {
                            // 流式分片下发
                            if (chunk.containsKey(FIELD_TYPE)) {
                                sendRawMessage(session, chunk);
                            } else {
                                sendMessage(session, MSG_TYPE_VOICE_CHUNK, chunk);
                            }
                            // 拼接完整回复
                            Object segment = chunk.get(FIELD_SEGMENT);
                            if (segment instanceof String str && !str.isBlank()) {
                                replyBuilder.append(str);
                            }
                        },
                        error -> {
                            logger.error("AI 语音对话异常，会话ID:{}", sessionId, error);
                            String errorMsg = "抱歉，我遇到了一些问题。";
                            sendMessage(session, MSG_TYPE_VOICE_RESPONSE,
                                    Map.of("text", errorMsg, "status", "error"));
                            rustPBXService.sendTTS(rustpbxSessionId, errorMsg, TTS_VOICE);
                            activeSubscriptions.remove(sessionId);
                        },
                        () -> {
                            // 流结束，执行 TTS
                            String fullReply = replyBuilder.toString();
                            if (!fullReply.isBlank()) {
                                onVoiceResponseComplete(session, fullReply);
                            }
                            activeSubscriptions.remove(sessionId);
                        }
                );

        activeSubscriptions.put(sessionId, subscription);
    }

    /**
     * AI 完整回复生成完毕，执行 TTS 语音播放
     */
    public void onVoiceResponseComplete(WebSocketSession session, String aiReply) {
        String rustpbxSessionId = sessionRustpbxMap.get(session.getId());
        if (rustpbxSessionId != null && aiReply != null && !aiReply.isBlank()) {
            rustPBXService.sendTTS(rustpbxSessionId, aiReply, TTS_VOICE);
        }
    }

    /**
     * 挂断通话
     */
    private void handleHangup(WebSocketSession session) {
        String sessionId = session.getId();
        releaseSessionResource(sessionId);
        sendMessage(session, MSG_TYPE_HANGUP, null);
    }

    // 连接关闭
    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        String sessionId = session.getId();
        logger.info("语音信令连接断开，会话ID:{}，关闭状态:{}", sessionId, status);
        releaseSessionResource(sessionId);
    }

    /**
     * 统一释放会话资源
     */
    private void releaseSessionResource(String sessionId) {
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
        // 清理对话实例
        sessionChatServiceMap.remove(sessionId);
    }

    // 消息发送工具方法
    /**
     * 标准消息发送（包装 type + data）
     */
    private void sendMessage(WebSocketSession session, String type, Object data) {
        if (!session.isOpen()) {
            return;
        }
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put(FIELD_TYPE, type);
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
}