package com.leyon.backend.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.common.QuotaExceededException;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.Org;
import com.leyon.backend.entity.Record;
import com.leyon.backend.entity.Session;
import com.leyon.backend.service.AssistantService;
import com.leyon.backend.service.ChatService;
import com.leyon.backend.service.KnowledgeBaseService;
import com.leyon.backend.service.KnowledgeProvider;
import com.leyon.backend.service.ModelAdapter;
import com.leyon.backend.service.OrgService;
import com.leyon.backend.service.QuotaService;
import com.leyon.backend.service.RecordService;
import com.leyon.backend.service.SessionService;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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
    private static final String FIELD_MESSAGE = "message";

    /** 会话属性Key */
    private static final String SESSION_ATTR_USER_ID = "userId";

    /** WebSocket URL 查询参数：业务会话ID（session model） */
    private static final String QUERY_PARAM_SESSION_ID = "sessionId";

    /** 加载历史聊天的默认条数 */
    private static final int DEFAULT_HISTORY_LIMIT = 50;

    // 日志
    private final Logger logger = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    // 依赖注入
    private final ModelAdapter modelAdapter;
    private final KnowledgeProvider knowledgeProvider;
    private final AssistantService assistantService;
    private final RecordService recordService;
    private final SessionService sessionService;
    private final OrgService orgService;
    private final QuotaService quotaService;
    private final KnowledgeBaseService knowledgeBaseService;
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
    /** 会话ID -> 业务会话ID（session model，可能为空） */
    private final ConcurrentHashMap<String, String> businessSessionMap = new ConcurrentHashMap<>();
    /** 已认证的会话ID集合 */
    private final ConcurrentHashMap<String, Boolean> authenticatedSessions = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ModelAdapter modelAdapter,
                                KnowledgeProvider knowledgeProvider,
                                AssistantService assistantService,
                                RecordService recordService,
                                SessionService sessionService,
                                OrgService orgService,
                                QuotaService quotaService,
                                KnowledgeBaseService knowledgeBaseService,
                                ObjectMapper objectMapper,
                                JwtUtil jwtUtil,
                                ToolRegistry toolRegistry) {
        this.modelAdapter = modelAdapter;
        this.knowledgeProvider = knowledgeProvider;
        this.assistantService = assistantService;
        this.recordService = recordService;
        this.sessionService = sessionService;
        this.orgService = orgService;
        this.quotaService = quotaService;
        this.knowledgeBaseService = knowledgeBaseService;
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

        // 校验当前用户是否有权限访问该助手（个人数据按 userId；组织数据按成员 viewer 以上可读/使用）
        if (!canUseAssistant(assistant, userId)) {
            sendMessage(session, MSG_TYPE_ERROR, "无权访问此助手");
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        // 绑定会话与助手
        sessionAssistantMap.put(sessionId, assistantId);

        // 解析可选业务会话ID（session model），并校验归属
        String businessSessionId = resolveQueryParam(session, QUERY_PARAM_SESSION_ID);
        if (StringUtils.hasText(businessSessionId)) {
            Session bizSession = sessionService.getOwned(businessSessionId, userId);
            if (bizSession == null) {
                sendMessage(session, MSG_TYPE_ERROR, "会话不存在或无访问权限");
                closeSession(session);
                return;
            }
            if (!assistantId.equals(bizSession.getAssistantId())) {
                sendMessage(session, MSG_TYPE_ERROR, "会话与助手不匹配");
                closeSession(session);
                return;
            }
            businessSessionMap.put(sessionId, businessSessionId);
        }

        // 从服务端持久化的 knowledge_ids 加载知识库关联（与当前用户可见数据集求交后再注入）
        List<String> knowledgeIds = retainVisibleKnowledgeIds(
                parseKnowledgeIds(assistant.getKnowledgeIds()), userId);

        // 初始化聊天实例（工具集按助手白名单裁剪，白名单为空即全部可用）
        ChatService chatService = new ChatService(
                modelAdapter, knowledgeProvider, objectMapper,
                assistant.getPersonality(), knowledgeIds,
                toolRegistry.resolveToolCallbacks(assistant.getToolList())
        );
        // 应用助手级模型参数（覆盖全局默认）
        chatService.setModelParams(assistant.getModelName(), assistant.getTemperature(), assistant.getMaxTokens());

        // 加载历史聊天记录（优先按会话维度，未指定会话时按助手维度，限制最近50条避免内存溢出）
        List<Record> chatHistory;
        if (StringUtils.hasText(businessSessionId)) {
            chatHistory = recordService.listBySessionIdLimit(businessSessionId, DEFAULT_HISTORY_LIMIT);
        } else {
            chatHistory = recordService.listByAssistantIdLimit(assistantId, DEFAULT_HISTORY_LIMIT);
        }
        if (chatHistory != null && !chatHistory.isEmpty()) {
            chatService.loadChatHistory(chatHistory);
        }

        chatServices.put(sessionId, chatService);
        // 返回助手基础信息
        sendMessage(session, MSG_TYPE_ASSISTANT_INFO, assistant);
        logger.info("WebSocket 连接建立成功（已认证），会话ID:{}，助手ID:{}，业务会话ID:{}",
                sessionId, assistantId, businessSessionId);
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
            case MSG_TYPE_KB_SELECT -> handleSelectedKbIds(session, chatService, node);
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
        JsonNode contentNode = node.get(FIELD_CONTENT);
        String content = contentNode == null ? null : contentNode.asText();
        String userId = (String) session.getAttributes().get(SESSION_ATTR_USER_ID);

        // 输入形态校验先于配额校验：畸形/超长消息不该消耗一次数据库聚合
        if (!StringUtils.hasText(content)) {
            sendMessage(session, MSG_TYPE_ERROR, "消息内容不能为空");
            return;
        }
        if (content.length() > ChatService.MAX_INPUT_CHARS) {
            sendMessage(session, MSG_TYPE_ERROR,
                    "消息过长（最多 " + ChatService.MAX_INPUT_CHARS + " 字），请精简后重发");
            return;
        }

        // P2-10 消息配额拦截：单日消息量超限时回错误消息，不发起流式（WS 场景不抛 HTTP 异常）
        if (userId != null) {
            try {
                quotaService.checkSendMessage(userId);
            } catch (QuotaExceededException e) {
                sendMessage(session, MSG_TYPE_ERROR, e.getMessage());
                return;
            }
        }

        // 终止上一次未完成的流式请求
        Disposable oldSub = activeSubscriptions.get(sessionId);
        if (oldSub != null && !oldSub.isDisposed()) {
            oldSub.dispose();
        }

        // 订阅流式响应
        // 捕获流结束分片（含 message/costTime/knowledgebase/tokenUsage），用于收尾下发 query_end
        final AtomicReference<Map<String, Object>> endChunkRef = new AtomicReference<>();
        Disposable subscription = chatService.chatStream(content)
                .subscribe(
                        chunk -> {
                            if (Boolean.TRUE.equals(chunk.get(FIELD_STREAM_END))) {
                                endChunkRef.set(chunk);
                            }
                            if (chunk.containsKey(FIELD_TYPE) && !chunk.containsKey(FIELD_SEGMENT)) {
                                sendRawMessage(session, chunk);
                            } else {
                                sendMessage(session, MSG_TYPE_ASSISTANT_MSG, chunk);
                            }
                        },
                        error -> {
                            logger.error("流式对话异常，会话ID:{}", sessionId, error);
                            sendMessage(session, MSG_TYPE_ASSISTANT_MSG, endChunkOrMarker(endChunkRef.get()));
                            sendMessage(session, MSG_TYPE_QUERY_END, endChunkOrMarker(endChunkRef.get()));
                            activeSubscriptions.remove(sessionId);
                        },
                        () -> {
                            // 正常收尾：前端依据 query_end 解除打字态并渲染耗时/引用/Token 诊断
                            sendMessage(session, MSG_TYPE_QUERY_END, endChunkOrMarker(endChunkRef.get()));
                            activeSubscriptions.remove(sessionId);
                        }
                );

        activeSubscriptions.put(sessionId, subscription);
    }

    /**
     * 取流结束分片；流未产出结束分片（异常/空流）时补一个最小收尾标记，
     * 保证前端必定收到收尾帧，不会停在打字态
     */
    private Map<String, Object> endChunkOrMarker(Map<String, Object> endChunk) {
        if (endChunk != null) {
            return endChunk;
        }
        Map<String, Object> marker = new HashMap<>();
        marker.put(FIELD_SEGMENT, "");
        marker.put(FIELD_STREAM_END, true);
        marker.put(FIELD_MESSAGE, "");
        return marker;
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
     * 客户端上报的数据集ID须与当前用户可见范围求交，避免越权检索他人/他组织知识库内容
     */
    private void handleSelectedKbIds(@NonNull WebSocketSession session, ChatService chatService, JsonNode node) {
        List<String> kbIds = objectMapper.convertValue(
                node.get(FIELD_IDS),
                new TypeReference<List<String>>() {}
        );
        String userId = (String) session.getAttributes().get(SESSION_ATTR_USER_ID);
        chatService.updateDataset(retainVisibleKnowledgeIds(kbIds, userId));
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
        businessSessionMap.remove(sessionId);
        authenticatedSessions.remove(sessionId);
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
            // 人设是跨会话共享的助手配置：仅属主（个人）或组织 editor 以上可回写，
            // 否则组织 viewer 成员可通过对话改写共享助手提示词
            if (personality != null && canEditAssistant(assistantService.getById(assistantId),
                    (String) session.getAttributes().get(SESSION_ATTR_USER_ID))) {
                Assistant assistant = new Assistant();
                assistant.setId(assistantId);
                assistant.setPersonality(personality);
                assistantService.update(assistant);
            }
        } catch (Exception e) {
            logger.error("保存助手状态失败，会话ID:{}，助手ID:{}", sessionId, assistantId, e);
        }

        // 持久化本次会话新增的对话记录（修复对话历史无法落库的问题）
        persistNewRecords(assistantId, businessSessionMap.get(sessionId), chatService);
    }

    /**
     * 校验当前用户是否可读取/使用助手（P2-10 组织共享）
     * 个人数据按 userId；组织数据按成员 viewer 以上
     */
    private boolean canUseAssistant(Assistant assistant, String userId) {
        if (assistant == null || !StringUtils.hasText(userId)) {
            return false;
        }
        if (StringUtils.hasText(assistant.getOrgId())) {
            return orgService.isMember(assistant.getOrgId(), userId);
        }
        return userId.equals(assistant.getUserId());
    }

    /**
     * 校验当前用户是否可修改助手配置（与 AssistantController 的管理校验一致）
     * 个人数据按 userId；组织数据需 editor(含)以上
     */
    private boolean canEditAssistant(Assistant assistant, String userId) {
        if (assistant == null || !StringUtils.hasText(userId)) {
            return false;
        }
        if (StringUtils.hasText(assistant.getOrgId())) {
            return orgService.hasRoleAtLeast(assistant.getOrgId(), userId, Org.ROLE_EDITOR);
        }
        return userId.equals(assistant.getUserId());
    }

    /**
     * 收敛知识库范围：仅保留当前用户可见的 RAGFlow 数据集ID
     * 助手持久化的 knowledge_ids 与客户端上报的 selectedKbIds 均可能携带他人数据集，须服务端求交
     *
     * @param datasetIds 候选数据集ID列表
     * @param userId     当前用户ID
     * @return 可见的数据集ID列表（全不可见时为空，即本轮不检索知识库）
     */
    private List<String> retainVisibleKnowledgeIds(List<String> datasetIds, String userId) {
        if (datasetIds == null || datasetIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> retained = knowledgeBaseService.retainVisibleDatasetIds(datasetIds, userId);
        if (retained.size() < datasetIds.size()) {
            logger.warn("用户:{} 请求的数据集 {} 个中有 {} 个不可见，已按可见范围收敛",
                    userId, datasetIds.size(), datasetIds.size() - retained.size());
        }
        return retained;
    }

    /**
     * 批量落库本次会话新增的对话记录
     *
     * @param assistantId       助手ID
     * @param businessSessionId 业务会话ID（可能为空，空则不关联会话）
     * @param chatService       聊天服务实例
     */
    private void persistNewRecords(String assistantId, String businessSessionId, ChatService chatService) {
        List<Record> newRecords = chatService.getNewRecords();
        if (newRecords == null || newRecords.isEmpty()) {
            return;
        }
        int saved = 0;
        String firstUserMessage = null;
        for (Record record : newRecords) {
            if (!StringUtils.hasText(record.getMessage())) {
                continue;
            }
            if (firstUserMessage == null && Record.ROLE_USER == record.getRole()) {
                firstUserMessage = record.getMessage();
            }
            record.setId(null); // 由 MyBatis-Plus 自动生成 UUID
            record.setAssistantId(assistantId);
            if (businessSessionId != null) {
                record.setSessionId(businessSessionId);
            }
            record.setIsDeleted(Record.NOT_DELETED);
            try {
                recordService.add(record);
                saved++;
            } catch (Exception e) {
                logger.error("保存对话记录失败，助手ID:{}，role:{}", assistantId, record.getRole(), e);
            }
        }
        if (saved > 0) {
            logger.info("已持久化 {} 条对话记录，助手ID:{}，业务会话ID:{}", saved, assistantId, businessSessionId);
            // 会话首轮对话后自动生成标题（取首条用户消息前缀）
            if (businessSessionId != null) {
                sessionService.autoTitleIfNeeded(businessSessionId, firstUserMessage);
            }
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
     * 从 WebSocket URL 查询参数中解析指定参数值
     *
     * @param session WebSocket 会话
     * @param name    参数名
     * @return 参数值，不存在返回 null
     */
    private String resolveQueryParam(@NonNull WebSocketSession session, String name) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) {
            return null;
        }
        for (String pair : uri.getQuery().split("&")) {
            int eqIndex = pair.indexOf('=');
            if (eqIndex < 0) {
                continue;
            }
            String key = pair.substring(0, eqIndex);
            if (name.equals(key)) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(eqIndex + 1), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return pair.substring(eqIndex + 1);
                }
            }
        }
        return null;
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