package com.leyon.backend.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.Record;
import com.leyon.backend.service.AssistantService;
import com.leyon.backend.service.ChatService;
import com.leyon.backend.service.KnowledgeProvider;
import com.leyon.backend.service.ModelAdapter;
import com.leyon.backend.service.RecordService;
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

    // 协议版本
    /** 当前 WebSocket 通信协议版本 */
    private static final String PROTOCOL_VERSION = "1.0.0";
    /** 协议版本字段名 */
    private static final String FIELD_PROTOCOL_VERSION = "protocolVersion";

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
    private static final String MSG_TYPE_AUTH = "auth";
    private static final String MSG_TYPE_PING = "ping";
    private static final String MSG_TYPE_PONG = "pong";

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
    private final ModelAdapter modelAdapter;
    private final KnowledgeProvider knowledgeProvider;
    private final AssistantService assistantService;
    private final RecordService recordService;
    private final ObjectMapper objectMapper;
    private final JwtUtil jwtUtil;
    private final ToolRegistry toolRegistry;

    // 会话内存缓存
    /** 会话ID -> 聊天实例 */
    private final ConcurrentHashMap<String, ChatService> chatServices = new ConcurrentHashMap<>();
    /** 会话ID -> 流式订阅器 */
    private final ConcurrentHashMap<String, Disposable> activeSubscriptions = new ConcurrentHashMap<>();
    /** 会话ID -> 助手ID */
    private final ConcurrentHashMap<String, String> sessionAssistantMap = new ConcurrentHashMap<>();
    /** 已认证的会话ID集合 */
    private final ConcurrentHashMap<String, Boolean> authenticatedSessions = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ModelAdapter modelAdapter,
                                KnowledgeProvider knowledgeProvider,
                                AssistantService assistantService,
                                RecordService recordService,
                                ObjectMapper objectMapper,
                                JwtUtil jwtUtil,
                                ToolRegistry toolRegistry) {
        this.modelAdapter = modelAdapter;
        this.knowledgeProvider = knowledgeProvider;
        this.assistantService = assistantService;
        this.recordService = recordService;
        this.objectMapper = objectMapper;
        this.jwtUtil = jwtUtil;
        this.toolRegistry = toolRegistry;
    }

    // 连接建立
    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        String sessionId = session.getId();
        try {
            // 检查是否已在握手阶段完成认证
            String userId = (String) session.getAttributes().get(SESSION_ATTR_USER_ID);
            if (userId != null && !userId.isBlank()) {
                // 握手阶段已认证，直接初始化会话
                authenticatedSessions.put(sessionId, true);
                initializeChatSession(session, sessionId, userId);
            }
            // 否则等待客户端发送 auth 消息
            logger.info("WebSocket 连接已建立，等待认证消息，会话ID:{}", sessionId);
        } catch (Exception e) {
            logger.error("WebSocket 连接初始化异常，会话ID:{}", sessionId, e);
            sendMessage(session, MSG_TYPE_ERROR, "连接初始化失败");
            closeSession(session);
        }
    }

    /**
     * 认证通过后初始化聊天会话
     * @throws IOException 
     */
    private void initializeChatSession(WebSocketSession session, String sessionId, String userId) throws IOException {
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
        if (!userId.equals(assistant.getUserId())) {
            sendMessage(session, MSG_TYPE_ERROR, "无权访问此助手");
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        // 绑定会话与助手
        sessionAssistantMap.put(sessionId, assistantId);

        // 从服务端持久化的 knowledge_ids 加载知识库关联
        List<String> knowledgeIds = parseKnowledgeIds(assistant.getKnowledgeIds());

        // 初始化聊天实例
        ChatService chatService = new ChatService(
                modelAdapter, knowledgeProvider, objectMapper,
                assistant.getPersonality(), knowledgeIds, toolRegistry.getAllToolCallbacks()
        );
        // 应用助手级模型参数（覆盖全局默认）
        chatService.setModelParams(assistant.getModelName(), assistant.getTemperature(), assistant.getMaxTokens());

        // 加载历史聊天记录（限制最近50条，避免全量加载导致内存溢出）
        List<Record> chatHistory = recordService.listByAssistantIdLimit(assistantId, 50);
        if (chatHistory != null && !chatHistory.isEmpty()) {
            chatService.loadChatHistory(chatHistory);
        }

        chatServices.put(sessionId, chatService);
        // 返回助手基础信息
        sendMessage(session, MSG_TYPE_ASSISTANT_INFO, assistant);
        logger.info("WebSocket 连接建立成功（已认证），会话ID:{}，助手ID:{}", sessionId, assistantId);
    }

    // 接收客户端消息
    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        String sessionId = session.getId();

        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.get(FIELD_TYPE).asText();

            // 处理认证消息（在会话初始化之前）
            if (MSG_TYPE_AUTH.equals(type)) {
                handleAuth(session, sessionId, node);
                return;
            }

            // 非认证消息需要会话已初始化
            ChatService chatService = chatServices.get(sessionId);
            if (chatService == null) {
                sendMessage(session, MSG_TYPE_ERROR, "Session not initialized or not authenticated");
                return;
            }
            dispatchMessage(session, chatService, type, node);
        } catch (IOException e) {
            logger.error("解析消息JSON失败，会话ID:{}", sessionId, e);
            sendMessage(session, MSG_TYPE_ERROR, "消息格式解析失败");
        } catch (Exception e) {
            logger.error("处理消息异常，会话ID:{}", sessionId, e);
            // 安全改进：不再将异常详情返回给客户端
            sendMessage(session, MSG_TYPE_ERROR, "消息处理失败，请重试");
        }
    }

    /**
     * 处理认证消息（首条消息携带 token）
     * @throws IOException 
     */
    private void handleAuth(WebSocketSession session, String sessionId, JsonNode node) throws IOException {
        // 已认证则忽略重复认证请求
        if (authenticatedSessions.containsKey(sessionId)) {
            return;
        }

        String token = node.path("token").asText("");
        if (token.isBlank() || !jwtUtil.validateToken(token)) {
            sendMessage(session, MSG_TYPE_ERROR, "认证失败：无效的 Token");
            closeSession(session);
            return;
        }

        String userId = jwtUtil.getUserIdFromToken(token);
        session.getAttributes().put(SESSION_ATTR_USER_ID, userId);
        authenticatedSessions.put(sessionId, true);

        logger.info("WebSocket 认证成功，会话ID:{}，用户ID:{}", sessionId, userId);
        // 认证通过后初始化聊天会话
        initializeChatSession(session, sessionId, userId);
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
            case MSG_TYPE_PING -> sendMessage(session, MSG_TYPE_PONG, null);
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
     * 保存会话最终状态（更新助手人设 + 落库新增对话记录）
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

        // 持久化本次会话新增的对话记录（修复对话历史无法落库的问题）
        persistNewRecords(assistantId, chatService);
    }

    /**
     * 批量落库本次会话新增的对话记录
     *
     * @param assistantId 助手ID
     * @param chatService 聊天服务实例
     */
    private void persistNewRecords(String assistantId, ChatService chatService) {
        List<Record> newRecords = chatService.getNewRecords();
        if (newRecords == null || newRecords.isEmpty()) {
            return;
        }
        int saved = 0;
        for (Record record : newRecords) {
            if (!StringUtils.hasText(record.getMessage())) {
                continue;
            }
            record.setId(null); // 由 MyBatis-Plus 自动生成 UUID
            record.setAssistantId(assistantId);
            record.setIsDeleted(Record.NOT_DELETED);
            try {
                recordService.add(record);
                saved++;
            } catch (Exception e) {
                logger.error("保存对话记录失败，助手ID:{}，role:{}", assistantId, record.getRole(), e);
            }
        }
        if (saved > 0) {
            logger.info("已持久化 {} 条对话记录，助手ID:{}", saved, assistantId);
        }
    }

    // 工具方法
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
     * 标准消息发送（包装 type + data + protocolVersion）
     */
    private void sendMessage(WebSocketSession session, String type, Object data) {
        if (!session.isOpen()) {
            return;
        }
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put(FIELD_TYPE, type);
            msg.put(FIELD_PROTOCOL_VERSION, PROTOCOL_VERSION);
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