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
import org.springframework.ai.tool.ToolCallback;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.Disposable;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ChatModel chatModel;
    private final KnowledgeService knowledgeService;
    private final AssistantService assistantService;
    private final RecordService recordService;
    private final ObjectMapper objectMapper;
    private final List<ToolCallback> toolCallbacks;

    private final ConcurrentHashMap<String, ChatService> chatServices = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Disposable> activeSubscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionAssistantMap = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ChatModel chatModel, KnowledgeService knowledgeService,
                                AssistantService assistantService, RecordService recordService,
                                ObjectMapper objectMapper, List<ToolCallback> toolCallbacks) {
        this.chatModel = chatModel;
        this.knowledgeService = knowledgeService;
        this.assistantService = assistantService;
        this.recordService = recordService;
        this.objectMapper = objectMapper;
        this.toolCallbacks = toolCallbacks != null ? toolCallbacks : new ArrayList<>();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String assistantId = getAssistantId(session);
        if (assistantId == null) {
            sendMessage(session, "error", "Invalid assistant ID");
            session.close();
            return;
        }

        Assistant assistant = assistantService.getById(assistantId);
        if (assistant == null) {
            sendMessage(session, "error", "Assistant not found");
            session.close();
            return;
        }

        // 从 WebSocket session attributes 中获取认证拦截器存入的 userId
        String userId = (String) session.getAttributes().get("userId");
        if (userId == null || !userId.equals(assistant.getUserId())) {
            sendMessage(session, "error", "无权访问此助手");
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        sessionAssistantMap.put(session.getId(), assistantId);

        // 知识库由前端通过 selectedKbIds 消息动态选择，初始为空
        List<String> knowledgeIds = List.of();
        ChatService chatService = new ChatService(chatModel, knowledgeService, objectMapper,
                assistant.getPersonality(), knowledgeIds, toolCallbacks);

        // 从 records 表加载历史聊天记录
        List<Record> chatHistory = recordService.listByAssistantId(assistantId);
        if (chatHistory != null && !chatHistory.isEmpty()) {
            chatService.loadChatHistory(chatHistory);
        }

        chatServices.put(session.getId(), chatService);
        sendMessage(session, "assistant_info", assistant);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = session.getId();
        ChatService chatService = chatServices.get(sessionId);
        if (chatService == null) {
            sendMessage(session, "error", "Session not initialized");
            return;
        }

        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.get("type").asText();

            switch (type) {
                case "chat" -> handleChat(session, chatService, node);
                case "prompt" -> handlePrompt(chatService, node);
                case "selectedKbIds" -> handleSelectedKbIds(chatService, node);
                case "resetMessage" -> chatService.reset();
                case "close" -> handleClose(session, chatService);
                default -> sendMessage(session, "error", "Unknown message type: " + type);
            }
        } catch (Exception e) {
            sendMessage(session, "error", "Failed to process message: " + e.getMessage());
        }
    }

    private void handleChat(WebSocketSession session, ChatService chatService, JsonNode node) {
        String text = node.get("content").asText();

        Disposable previous = activeSubscriptions.get(session.getId());
        if (previous != null && !previous.isDisposed()) {
            previous.dispose();
        }

        Disposable subscription = chatService.chatStream(text)
                .subscribe(
                        chunk -> {
                            // 工具调用/结果消息已自带 type 字段，直接发送
                            if (chunk.containsKey("type") && !chunk.containsKey("segment")) {
                                sendRawMessage(session, chunk);
                            } else {
                                // 普通对话消息按原有格式包装
                                sendMessage(session, "assistant_message", chunk);
                            }
                        },
                        error -> {
                            Map<String, Object> endData = new HashMap<>();
                            endData.put("segment", "");
                            endData.put("streamEnd", true);
                            sendMessage(session, "assistant_message", endData);
                            sendMessage(session, "query_end", null);
                            activeSubscriptions.remove(session.getId());
                        },
                        () -> activeSubscriptions.remove(session.getId())
                );

        activeSubscriptions.put(session.getId(), subscription);
    }

    private void handlePrompt(ChatService chatService, JsonNode node) {
        String prompt = node.get("content").asText();
        chatService.changePrompt(prompt);
    }

    private void handleSelectedKbIds(ChatService chatService, JsonNode node) {
        List<String> ids = objectMapper.convertValue(node.get("ids"),
                new TypeReference<List<String>>() {});
        chatService.updateDataset(ids);
    }

    private void handleClose(WebSocketSession session, ChatService chatService) throws IOException {
        saveChatState(session, chatService);
        session.close();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = session.getId();

        Disposable subscription = activeSubscriptions.remove(sessionId);
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }

        ChatService chatService = chatServices.remove(sessionId);
        if (chatService != null) {
            saveChatState(session, chatService);
        }

        sessionAssistantMap.remove(sessionId);
    }

    private void saveChatState(WebSocketSession session, ChatService chatService) {
        String assistantId = sessionAssistantMap.get(session.getId());
        if (assistantId == null) {
            return;
        }

        try {
            Map<String, String> state = chatService.close();
            // 仅保存人设变更，聊天记录已通过 RecordService 持久化到 records 表
            String personality = state.get("personality");
            if (personality != null) {
                Assistant assistant = new Assistant();
                assistant.setId(assistantId);
                assistant.setPersonality(personality);
                assistantService.update(assistant);
            }
        } catch (Exception ignored) {
        }
    }

    private String getAssistantId(WebSocketSession session) {
        if (session.getUri() == null) {
            return null;
        }
        String path = session.getUri().getPath();
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash >= path.length() - 1) {
            return null;
        }
        return path.substring(lastSlash + 1);
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

    /**
     * 直接发送原始消息（不额外包装 type/data 结构）
     * 用于工具调用/结果等自带完整结构的消息
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
        } catch (Exception ignored) {
        }
    }
}
