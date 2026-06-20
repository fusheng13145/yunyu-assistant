package com.leyon.backend.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.Record;
import com.leyon.backend.service.AssistantService;
import com.leyon.backend.service.ChatService;
import com.leyon.backend.service.KnowledgeService;
import com.leyon.backend.service.RecordService;
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

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天 WebSocket 处理器
 * 负责长连接建立、消息分发、流式对话、会话状态维护、历史记录加载与状态持久化
 *
 * @author leyon
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    // 常量定义
    /** 消息类型 */
    private static final String MSG_TYPE_CHAT = "chat";
    private static final String MSG_TYPE_PROMPT = "prompt";
    private static final String MSG_TYPE_KB_SELECT = "selectedKbIds";
    private static final String MSG_TYPE_RESET = "resetMessage";
    private static final String MSG_TYPE_CLOSE = "close";
    private static final String MSG_TYPE_ERROR = "error";
    private static final String MSG_TYPE_ASSISTANT_INFO = "assistant_info";
    private static final String MSG_TYPE_ASSISTANT_MSG = "assistant_message";
    private static final String MSG_TYPE_QUERY_END = "query_end";

    /** 字段名 */
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_IDS = "ids";
    private static final String FIELD_SEGMENT = "segment";
    private static final String FIELD_STREAM_END = "streamEnd";

    /** 会话属性Key */
    private static final String SESSION_ATTR_USER_ID = "userId";

    // 日志
    private final Logger logger = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    // 依赖注入
    private final ChatModel chatModel;
    private final KnowledgeService knowledgeService;
    private final AssistantService assistantService;
    private final RecordService recordService;
    private final ObjectMapper objectMapper;
    private final List<ToolCallback> toolCallbacks;

    // 会话内存缓存
    /** 会话ID -> 聊天实例 */
    private final ConcurrentHashMap<String, ChatService> chatServices = new ConcurrentHashMap<>();
    /** 会话ID -> 流式订阅器 */
    private final ConcurrentHashMap<String, Disposable> activeSubscriptions = new ConcurrentHashMap<>();
    /** 会话ID -> 助手ID */
    private final ConcurrentHashMap<String, String> sessionAssistantMap = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ChatModel chatModel,
                                KnowledgeService knowledgeService,
                                AssistantService assistantService,
                                RecordService recordService,
                                ObjectMapper objectMapper,
                                List<ToolCallback> toolCallbacks) {
        this.chatModel = chatModel;
        this.knowledgeService = knowledgeService;
        this.assistantService = assistantService;
        this.recordService = recordService;
        this.objectMapper = objectMapper;
        this.toolCallbacks = toolCallbacks == null ? new ArrayList<>() : toolCallbacks;
    }

    // 连接建立
    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        String sessionId = session.getId();
        try {
            // 解析路径获取助手ID
            String assistantId = getAssistantId(session);
            if (assistantId == null) {
                sendMessage(session, MSG_TYPE_ERROR, "Invalid assistant ID");
                closeSession(session);
                return;
            }

            // 校验助手是否存在
            Assistant assistant = assistantService.getById(assistantId);
            if (assistant == null) {
                sendMessage(session, MSG_TYPE_ERROR, "Assistant not found");
                closeSession(session);
                return;
            }

            // 校验当前用户是否有权限访问该助手
            String userId = (String) session.getAttributes().get(SESSION_ATTR_USER_ID);
            if (userId == null || !userId.equals(assistant.getUserId())) {
                sendMessage(session, MSG_TYPE_ERROR, "无权访问此助手");
                session.close(CloseStatus.NOT_ACCEPTABLE);
                return;
            }

            // 绑定会话与助手
            sessionAssistantMap.put(sessionId, assistantId);

            // 初始化聊天实例
            List<String> knowledgeIds = new ArrayList<>();
            ChatService chatService = new ChatService(
                    chatModel, knowledgeService, objectMapper,
                    assistant.getPersonality(), knowledgeIds, toolCallbacks
            );

            // 加载历史聊天记录
            List<Record> chatHistory = recordService.listByAssistantId(assistantId);
            if (chatHistory != null && !chatHistory.isEmpty()) {
                chatService.loadChatHistory(chatHistory);
            }

            chatServices.put(sessionId, chatService);
            // 返回助手基础信息
            sendMessage(session, MSG_TYPE_ASSISTANT_INFO, assistant);
            logger.info("WebSocket 连接建立成功，会话ID:{}，助手ID:{}", sessionId, assistantId);
        } catch (Exception e) {
            logger.error("WebSocket 连接初始化异常，会话ID:{}", sessionId, e);
            sendMessage(session, MSG_TYPE_ERROR, "连接初始化失败");
            closeSession(session);
        }
    }

    // 接收客户端消息
    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        String sessionId = session.getId();
        ChatService chatService = chatServices.get(sessionId);
        if (chatService == null) {
            sendMessage(session, MSG_TYPE_ERROR, "Session not initialized");
            return;
        }

        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.get(FIELD_TYPE).asText();
            dispatchMessage(session, chatService, type, node);
        } catch (IOException e) {
            logger.error("解析消息JSON失败，会话ID:{}", sessionId, e);
            sendMessage(session, MSG_TYPE_ERROR, "消息格式解析失败");
        } catch (Exception e) {
            logger.error("处理消息异常，会话ID:{}", sessionId, e);
            sendMessage(session, MSG_TYPE_ERROR, "Failed to process message: " + e.getMessage());
        }
    }

    /**
     * 消息分发
     */
    private void dispatchMessage(@NonNull WebSocketSession session, ChatService chatService, String type, JsonNode node) {
        switch (type) {
            case MSG_TYPE_CHAT -> handleChat(session, chatService, node);
            case MSG_TYPE_PROMPT -> handlePrompt(chatService, node);
            case MSG_TYPE_KB_SELECT -> handleSelectedKbIds(chatService, node);
            case MSG_TYPE_RESET -> chatService.reset();
            case MSG_TYPE_CLOSE -> handleClose(session, chatService);
            default -> sendMessage(session, MSG_TYPE_ERROR, "Unknown message type: " + type);
        }
    }

    // 各类消息处理器
    /**
     * 处理流式对话请求
     */
    private void handleChat(@NonNull WebSocketSession session, ChatService chatService, JsonNode node) {
        String sessionId = session.getId();
        String content = node.get(FIELD_CONTENT).asText();

        // 终止上一次未完成的流式请求
        Disposable oldSub = activeSubscriptions.get(sessionId);
        if (oldSub != null && !oldSub.isDisposed()) {
            oldSub.dispose();
        }

        // 订阅流式响应
        Disposable subscription = chatService.chatStream(content)
                .subscribe(
                        chunk -> {
                            if (chunk.containsKey(FIELD_TYPE) && !chunk.containsKey(FIELD_SEGMENT)) {
                                sendRawMessage(session, chunk);
                            } else {
                                sendMessage(session, MSG_TYPE_ASSISTANT_MSG, chunk);
                            }
                        },
                        error -> {
                            logger.error("流式对话异常，会话ID:{}", sessionId, error);
                            Map<String, Object> endData = new HashMap<>();
                            endData.put(FIELD_SEGMENT, "");
                            endData.put(FIELD_STREAM_END, true);
                            sendMessage(session, MSG_TYPE_ASSISTANT_MSG, endData);
                            sendMessage(session, MSG_TYPE_QUERY_END, null);
                            activeSubscriptions.remove(sessionId);
                        },
                        () -> activeSubscriptions.remove(sessionId)
                );

        activeSubscriptions.put(sessionId, subscription);
    }

    /**
     * 更新人设提示词
     */
    private void handlePrompt(ChatService chatService, JsonNode node) {
        String prompt = node.get(FIELD_CONTENT).asText();
        chatService.changePrompt(prompt);
    }

    /**
     * 切换选中知识库
     */
    private void handleSelectedKbIds(ChatService chatService, JsonNode node) {
        List<String> kbIds = objectMapper.convertValue(
                node.get(FIELD_IDS),
                new TypeReference<List<String>>() {}
        );
        chatService.updateDataset(kbIds);
    }

    /**
     * 主动关闭连接（内部捕获IO异常，消除编译报错）
     */
    private void handleClose(@NonNull WebSocketSession session, ChatService chatService) {
        try {
            saveChatState(session, chatService);
            session.close();
        } catch (IOException e) {
            logger.error("主动关闭WebSocket会话失败", e);
        }
    }

    // 连接关闭
    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        String sessionId = session.getId();
        logger.info("WebSocket 连接断开，会话ID:{}，关闭状态:{}", sessionId, status);

        // 停止流式订阅
        Disposable subscription = activeSubscriptions.remove(sessionId);
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }

        // 持久化状态 & 清理缓存
        ChatService chatService = chatServices.remove(sessionId);
        if (chatService != null) {
            saveChatState(session, chatService);
        }
        sessionAssistantMap.remove(sessionId);
    }

    // 状态持久化
    /**
     * 保存会话最终状态（仅更新助手人设）
     */
    private void saveChatState(@NonNull WebSocketSession session, ChatService chatService) {
        String sessionId = session.getId();
        String assistantId = sessionAssistantMap.get(sessionId);
        if (assistantId == null) {
            return;
        }

        try {
            Map<String, String> state = chatService.close();
            String personality = state.get("personality");
            if (personality != null) {
                Assistant assistant = new Assistant();
                assistant.setId(assistantId);
                assistant.setPersonality(personality);
                assistantService.update(assistant);
            }
        } catch (Exception e) {
            logger.error("保存助手状态失败，会话ID:{}，助手ID:{}", sessionId, assistantId, e);
        }
    }

    // 工具方法
    /**
     * 从请求路径解析 assistantId
     */
    private String getAssistantId(@NonNull WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            return null;
        }
        String path = uri.getPath();
        int lastSlashIndex = path.lastIndexOf('/');
        if (lastSlashIndex < 0 || lastSlashIndex >= path.length() - 1) {
            return null;
        }
        return path.substring(lastSlashIndex + 1);
    }

    /**
     * 统一关闭会话
     */
    private void closeSession(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close();
            }
        } catch (IOException e) {
            logger.error("关闭 WebSocket 会话异常", e);
        }
    }

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
     * 发送原始JSON消息（不额外包装）
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