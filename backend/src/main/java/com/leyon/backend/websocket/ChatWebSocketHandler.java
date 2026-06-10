package com.leyon.backend.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.ChatMessage;
import com.leyon.backend.service.AssistantService;
import com.leyon.backend.service.ChatService;
import com.leyon.backend.service.KnowledgeService;
import java.io.IOException;
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
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, ChatService> chatServices = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Disposable> activeSubscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionAssistantMap = new ConcurrentHashMap<>();

    private static final List<String> TOOL_FUNCTIONS = List.of("get_weather", "web_search", "get_current_datetime");

    public ChatWebSocketHandler(ChatModel chatModel, KnowledgeService knowledgeService,
                                AssistantService assistantService, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.knowledgeService = knowledgeService;
        this.assistantService = assistantService;
        this.objectMapper = objectMapper;
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

        sessionAssistantMap.put(session.getId(), assistantId);

        List<String> knowledgeIds = parseKnowledgeIds(assistant.getKnowledgeIds());
        ChatService chatService = new ChatService(chatModel, knowledgeService, objectMapper,
                assistant.getPersonality(), knowledgeIds, TOOL_FUNCTIONS);

        if (assistant.getChatMessage() != null && !assistant.getChatMessage().isEmpty()) {
            try {
                List<ChatMessage> chatHistory = objectMapper.readValue(
                        assistant.getChatMessage(),
                        new TypeReference<List<ChatMessage>>() {}
                );
                chatService.loadChatHistory(chatHistory);
            } catch (Exception ignored) {
            }
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
                        chunk -> sendMessage(session, "assistant_message", chunk),
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
            Assistant assistant = new Assistant();
            assistant.setId(assistantId);
            assistant.setChatMessage(state.get("chatMessage"));
            assistant.setPersonality(state.get("personality"));
            assistant.setKnowledgeIds(state.get("knowledgeIds"));
            assistantService.update(assistant);
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
}
