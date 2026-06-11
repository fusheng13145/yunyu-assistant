package com.leyon.backend.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.Record;
import com.leyon.backend.service.AssistantService;
import com.leyon.backend.service.ChatService;
import com.leyon.backend.service.KnowledgeService;
import com.leyon.backend.service.RecordService;
import com.leyon.backend.service.RustPBXService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class VoiceSignalingHandler extends TextWebSocketHandler {

    private final RustPBXService rustPBXService;
    private final AssistantService assistantService;
    private final ChatModel chatModel;
    private final KnowledgeService knowledgeService;
    private final RecordService recordService;
    private final ObjectMapper objectMapper;
    private final List<ToolCallback> toolCallbacks;

    private final ConcurrentHashMap<String, String> sessionRustpbxMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ChatService> sessionChatServiceMap = new ConcurrentHashMap<>();

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
        this.toolCallbacks = toolCallbacks != null ? toolCallbacks : List.of();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sendMessage(session, "connected", null);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.get("type").asText();

            switch (type) {
                case "offer" -> handleOffer(session, node);
                case "webrtc_connected" -> handleWebRtcConnected(session, node);
                case "hangup" -> handleHangup(session);
                default -> sendMessage(session, "error", "Unknown message type: " + type);
            }
        } catch (Exception e) {
            sendMessage(session, "error", "Failed to process message: " + e.getMessage());
        }
    }

    /**
     * 处理 WebRTC Offer：建立与语音网关的连接
     */
    private void handleOffer(WebSocketSession session, JsonNode node) {
        String offerSDP = node.get("sdp").asText();
        String assistantId = node.has("assistantId") ? node.get("assistantId").asText() : null;

        try {
            // 为该会话创建对应的 ChatService（用于语音对话中的 AI 回复）
            ChatService chatService = null;
            if (assistantId != null) {
                Assistant assistant = assistantService.getById(assistantId);
                if (assistant != null) {
                    String userId = (String) session.getAttributes().get("userId");
                    if (userId == null || !userId.equals(assistant.getUserId())) {
                        sendMessage(session, "error", "无权访问此助手");
                        return;
                    }
                    // 知识库：通过 RecordService 从 DB 加载历史记录
                    List<String> knowledgeIds = new ArrayList<>(); // 知识库ID后续可通过知识库服务获取
                    chatService = new ChatService(chatModel, knowledgeService, objectMapper,
                            assistant.getPersonality(), knowledgeIds, toolCallbacks);
                    // 加载历史聊天记录（从 Record 表）
                    try {
                        List<Record> history = recordService.listByAssistantId(assistantId);
                        if (!history.isEmpty()) {
                            chatService.loadChatHistory(history);
                        }
                    } catch (Exception ignored) {}
                    sessionChatServiceMap.put(session.getId(), chatService);
                }
            }

            String sessionId = rustPBXService.connectToRustPBX(offerSDP, assistantId,
                    answer -> sendMessage(session, "answer", Map.of("sdp", answer)),
                    asrText -> handleAsrResult(session, asrText));

            sessionRustpbxMap.put(session.getId(), sessionId);
        } catch (Exception e) {
            sendMessage(session, "error", "Failed to connect to voice server: " + e.getMessage());
        }
    }

    /**
     * 处理 WebRTC 连接建立完成事件
     */
    private void handleWebRtcConnected(WebSocketSession session, JsonNode node) {
        String greeting = node.has("greeting") ? node.get("greeting").asText()
                : "你好，我是云谕助手，有什么可以帮助你的吗？";
        String sessionId = session.getId();
        String rustpbxSessionId = sessionRustpbxMap.get(sessionId);

        if (rustpbxSessionId != null) {
            // 播放欢迎语（TTS）
            rustPBXService.sendTTS(rustpbxSessionId, greeting, "longxiaochun");
        }
        sendMessage(session, "webrtc_connected", null);
    }

    /**
     * 处理 ASR 语音识别结果 — 核心语音全链路：
     * ASR 文本 → ChatService AI 对话 → TTS 语音合成播放
     */
    private void handleAsrResult(WebSocketSession session, String asrText) {
        if (asrText == null || asrText.isBlank()) {
            return;
        }

        // 将 ASR 文本转发给前端显示（用户说了什么）
        sendMessage(session, "asr_text", Map.of("text", asrText));

        ChatService chatService = sessionChatServiceMap.get(session.getId());
        String rustpbxSessionId = sessionRustpbxMap.get(session.getId());

        if (chatService == null || rustpbxSessionId == null) {
            // 没有 ChatService 时，仅做简单回显
            sendMessage(session, "voice_response",
                    Map.of("text", "抱歉，语音助手未就绪，请稍后重试。", "status", "error"));
            return;
        }

        // 调用 AI 对话获取回复
        StringBuilder replyBuilder = new StringBuilder();
        chatService.chatStream(asrText)
                .subscribe(
                        chunk -> {
                            // 流式文本段转发给前端
                            if (chunk.containsKey("type")) {
                                sendRawMessage(session, chunk);
                            } else {
                                sendMessage(session, "voice_chunk", chunk);
                            }
                            // 收集完整的 AI 回复文本
                            Object segment = chunk.get("segment");
                            if (segment instanceof String str && !str.isEmpty()) {
                                replyBuilder.append(str);
                            }
                        },
                        error -> {
                            String errorMsg = "抱歉，我遇到了一些问题。";
                            sendMessage(session, "voice_response",
                                    Map.of("text", errorMsg, "status", "error"));
                            if (rustpbxSessionId != null) {
                                rustPBXService.sendTTS(rustpbxSessionId, errorMsg, "longxiaochun");
                            }
                        },
                        () -> {
                            // 流结束 — 触发 TTS 播放完整 AI 回复
                            String fullReply = replyBuilder.toString();
                            if (!fullReply.isEmpty()) {
                                onVoiceResponseComplete(session, fullReply);
                            }
                        }
                );
    }

    /**
     * 当收到完整的 AI 回复后触发 TTS 播放
     * 由 ChatService 的 streamEnd 消息触发此方法
     */
    public void onVoiceResponseComplete(WebSocketSession session, String aiReply) {
        String rustpbxSessionId = sessionRustpbxMap.get(session.getId());
        if (rustpbxSessionId != null && aiReply != null && !aiReply.isEmpty()) {
            // 将 AI 回复发送给 TTS 引擎进行语音合成
            rustPBXService.sendTTS(rustpbxSessionId, aiReply, "longxiaochun");
        }
    }

    private void handleHangup(WebSocketSession session) {
        String sessionId = session.getId();
        String rustpbxSessionId = sessionRustpbxMap.remove(sessionId);
        if (rustpbxSessionId != null) {
            rustPBXService.disconnect(rustpbxSessionId);
        }
        sessionChatServiceMap.remove(sessionId);
        sendMessage(session, "hangup", null);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = session.getId();
        String rustpbxSessionId = sessionRustpbxMap.remove(sessionId);
        if (rustpbxSessionId != null) {
            rustPBXService.disconnect(rustpbxSessionId);
        }
        sessionChatServiceMap.remove(sessionId);
    }

    private void sendMessage(WebSocketSession session, String type, Object data) {
        if (!session.isOpen()) {
            return;
        }
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", type);
            if (data != null) {
                msg.put("data", data);
            }
            String json = objectMapper.writeValueAsString(msg);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception ignored) {
        }
    }

    private void sendRawMessage(WebSocketSession session, Map<String, Object> message) {
        if (!session.isOpen()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(message);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception ignored) {
        }
    }

    private List<String> parseKnowledgeIds(String knowledgeIdsJson) {
        if (knowledgeIdsJson == null || knowledgeIdsJson.isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(knowledgeIdsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
