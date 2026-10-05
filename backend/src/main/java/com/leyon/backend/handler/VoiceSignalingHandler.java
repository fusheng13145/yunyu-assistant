package com.leyon.backend.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.common.QuotaExceededException;
import com.leyon.backend.entity.ApiApp;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.CallRecord;
import com.leyon.backend.entity.Record;
import com.leyon.backend.entity.WebhookDelivery;
import com.leyon.backend.service.ApiAppService;
import com.leyon.backend.service.AssistantPolicy;
import com.leyon.backend.service.AssistantService;
import com.leyon.backend.service.CallRecordService;
import com.leyon.backend.service.ChatService;
import com.leyon.backend.service.ConversationRecordWriter;
import com.leyon.backend.service.KnowledgeBaseService;
import com.leyon.backend.service.KnowledgeProvider;
import com.leyon.backend.service.ModelAdapter;
import com.leyon.backend.service.OrgService;
import com.leyon.backend.service.QuotaService;
import com.leyon.backend.service.RecordService;
import com.leyon.backend.service.RustPBXService;
import com.leyon.backend.service.WebhookService;
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

import java.net.URI;
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
    private static final String MSG_TYPE_PING = "ping";
    private static final String MSG_TYPE_PONG = "pong";

    /** 字段名 */
    private static final String FIELD_SDP = "sdp";
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
    /** 会话属性Key：第三方应用ID（OpenAPI 语音会话由 OpenApiWebSocketAuthInterceptor 注入） */
    private static final String SESSION_ATTR_APP_ID = "appId";

    // ====================== 日志 ======================
    private final Logger logger = LoggerFactory.getLogger(VoiceSignalingHandler.class);

    // 依赖注入（使用扩展抽象层接口，与 ChatWebSocketHandler 保持一致）
    private final RustPBXService rustPBXService;
    private final AssistantService assistantService;
    private final ModelAdapter modelAdapter;
    private final KnowledgeProvider knowledgeProvider;
    private final RecordService recordService;
    private final CallRecordService callRecordService;
    private final OrgService orgService;
    private final QuotaService quotaService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final WebhookService webhookService;
    private final ApiAppService apiAppService;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final JwtUtil jwtUtil;
    private final AssistantPolicy assistantPolicy;
    private final ConversationRecordWriter recordWriter;

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
    /** 会话ID -> 第三方应用ID（P2-17 Webhook；仅 OpenAPI 语音会话非空） */
    private final ConcurrentHashMap<String, String> sessionAppIdMap = new ConcurrentHashMap<>();
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
                                 OrgService orgService,
                                 QuotaService quotaService,
                                 KnowledgeBaseService knowledgeBaseService,
                                 WebhookService webhookService,
                                 ApiAppService apiAppService,
                                 ObjectMapper objectMapper,
                                 ToolRegistry toolRegistry,
                                 JwtUtil jwtUtil,
                                 AssistantPolicy assistantPolicy,
                                 ConversationRecordWriter recordWriter) {
        this.rustPBXService = rustPBXService;
        this.assistantService = assistantService;
        this.modelAdapter = modelAdapter;
        this.knowledgeProvider = knowledgeProvider;
        this.recordService = recordService;
        this.callRecordService = callRecordService;
        this.orgService = orgService;
        this.quotaService = quotaService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.webhookService = webhookService;
        this.apiAppService = apiAppService;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.jwtUtil = jwtUtil;
        this.assistantPolicy = assistantPolicy;
        this.recordWriter = recordWriter;
    }

    // 连接建立
    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        String sessionId = session.getId();
        // S-10（v2.80）：握手即鉴权（拦截器强制令牌并注入 userId），auth 帧延迟通道与回收器移除
        String userId = (String) session.getAttributes().get(SESSION_ATTR_USER_ID);
        if (userId == null || userId.isBlank()) {
            // 防御分支：拦截器口径若被放松，这里宁可关连接
            logger.warn("语音信令握手未携带身份，拒绝建立会话，会话ID:{}", sessionId);
            closeSession(session);
            return;
        }
        authenticatedSessions.put(sessionId, true);
        sendMessage(session, MSG_TYPE_CONNECTED, null);
        logger.info("语音信令连接建立（握手阶段已认证），会话ID:{}", sessionId);
    }

    // 接收客户端消息
    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        String sessionId = session.getId();

        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.get(FIELD_TYPE).asText();

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
        // 资格复核：握手到 offer 之间可能已被收紧能力或吊销（同一连接可多次发起通话）
        if (!openApiVoiceStillAllowed(session)) {
            logger.warn("开放语音通话被拒：会话ID:{} 的应用已不再具备语音能力", sessionId);
            sendMessage(session, MSG_TYPE_ERROR, "本应用的语音能力已关闭，连接即将结束");
            closeSession(session);
            return;
        }
        String offerSDP = node.get(FIELD_SDP).asText();
        // 助手ID只能来自握手 URL 路径段（/ws-voice/{assistantId}、/api/open/ws-voice/{assistantId}），
        // 不接受消息体传值：避免同一连接内切换到其他助手造成越权
        String assistantId = parseAssistantId(session);
        if (assistantId == null) {
            sendMessage(session, MSG_TYPE_ERROR, "缺少助手ID");
            return;
        }

        ChatService chatService = initChatService(session, assistantId);
        if (chatService == null) {
            return;
        }
        sessionChatServiceMap.put(sessionId, chatService);
        sessionAssistantMap.put(sessionId, assistantId);

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

        // 权限校验（个人数据按 userId；组织数据按成员 viewer 以上可发起通话）
        String userId = (String) session.getAttributes().get(SESSION_ATTR_USER_ID);
        if (userId == null || !canUseAssistant(assistant, userId)) {
            sendMessage(session, MSG_TYPE_ERROR, "无权访问此助手");
            return null;
        }

        // 缓存助手音色（TTS 播报使用）
        String voice = assistant.getVoice();
        if (StringUtils.hasText(voice)) {
            sessionVoiceMap.put(sessionId, voice);
        }

        // 从服务端持久化的 knowledge_ids 加载知识库关联（与通话者可见数据集求交）
        List<String> knowledgeIds = knowledgeBaseService.retainVisibleDatasetIds(
                parseKnowledgeIds(assistant.getKnowledgeIds()), userId);

        // 初始化对话服务（使用新的抽象接口依赖；工具集按助手白名单裁剪，语音助手若需 LLM 主动挂断须保留 hangup）
        // 人设与模型参数走 AssistantPolicy 钳制后的值：一次通话可持续数分钟，超限行透传进来的开销收不回来
        AssistantPolicy.Runtime runtime = assistantPolicy.runtime(assistant);
        ChatService chatService = new ChatService(
                modelAdapter, knowledgeProvider, objectMapper,
                runtime.personality(), knowledgeIds,
                toolRegistry.resolveToolCallbacks(assistant.getToolList())
        );
        // 注册挂断监听器：LLM 调用 hangup 工具时主动挂断通话
        chatService.setHangupListener(reason -> handleLlmHangup(session, reason));
        // 应用助手级模型参数（覆盖全局默认）
        chatService.setModelParams(runtime.model(), runtime.temperature(), runtime.maxTokens());

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
     * 从握手 URL 路径末段解析 assistantId（与 ChatWebSocketHandler 的 /ws/{assistantId} 对称）
     *
     * @return 助手ID，路径无末段时返回 null
     */
    private String parseAssistantId(@NonNull WebSocketSession session) {
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

        // 通话真正建立：先建通话记录（内含单日通话次数/时长配额校验），配额超限则直接终止本轮通话
        String callId;
        try {
            callId = createCallRecord(session);
        } catch (QuotaExceededException e) {
            logger.warn("语音通话配额超限，终止通话，会话ID:{}，{}", sessionId, e.getMessage());
            // 先发错误原因再断连：前端 onClose 会走统一的通话结束清理
            sendMessage(session, MSG_TYPE_ERROR, e.getMessage());
            releaseSessionResource(sessionId);
            closeSession(session);
            return;
        } catch (Exception e) {
            // 与配额超限同样的收场：没有通话记录就不要开始通话，否则时长/次数/用量三处都无从结算
            logger.error("创建通话记录失败，终止通话，会话ID:{}", sessionId, e);
            sendMessage(session, MSG_TYPE_ERROR, "通话记录创建失败，本次通话未能开始");
            releaseSessionResource(sessionId);
            closeSession(session);
            return;
        }
        if (rustpbxSessionId != null) {
            rustPBXService.sendTTS(rustpbxSessionId, greeting, sessionVoiceMap.get(sessionId));
        }
        // 下发 callId 供前端录音结束后回传上传
        Map<String, Object> data = new HashMap<>();
        data.put("callId", callId);
        sendMessage(session, MSG_TYPE_WEBRTC_CONNECTED, data);
    }

    /**
     * 创建通话记录（状态=进行中），返回通话记录ID
     *
     * <p>本方法不吞异常：建不成记录就没有这一通的账，放行等于让时长、次数与用量三处都无从结算。
     *
     * @throws QuotaExceededException 单日通话次数/时长配额超限
     * @throws IllegalStateException  会话尚未绑定助手与用户（offer 未成功，不该有通话正在进行）
     */
    private String createCallRecord(WebSocketSession session) {
        String sessionId = session.getId();
        String assistantId = sessionAssistantMap.get(sessionId);
        String userId = (String) session.getAttributes().get(SESSION_ATTR_USER_ID);
        if (!StringUtils.hasText(assistantId) || !StringUtils.hasText(userId)) {
            throw new IllegalStateException("会话未绑定助手或用户，会话ID:" + sessionId);
        }
        // 避免重复创建
        if (sessionCallRecordMap.containsKey(sessionId)) {
            return sessionCallRecordMap.get(sessionId);
        }
        // 建不成记录就没有这一通的账：配额异常与数据库异常一律向上抛，由调用方终止通话（不放行无记账的通话）
        CallRecord record = new CallRecord();
        record.setUserId(userId);
        record.setAssistantId(assistantId);
        // 组织归属从助手继承（P2-10：组织级配额统计与通话记录隔离依赖 org_id）
        Assistant assistant = assistantService.getById(assistantId);
        if (assistant != null) {
            record.setOrgId(assistant.getOrgId());
        }
        record.setStatus(CallRecord.STATUS_IN_PROGRESS);
        record.setDurationSec(0);
        record.setMessageCount(0);
        record.setStartedAt(LocalDateTime.now());
        record.setIsDeleted(CallRecord.NOT_DELETED);
        callRecordService.create(record);
        sessionCallRecordMap.put(sessionId, record.getId());

        // P2-17：记录调用方应用ID（OpenAPI 语音会话注入 appId，内部会话为空），仅第三方应用投递 call.connected
        String appId = (String) session.getAttributes().get(SESSION_ATTR_APP_ID);
        if (StringUtils.hasText(appId)) {
            sessionAppIdMap.put(sessionId, appId);
            Map<String, Object> payload = new HashMap<>();
            payload.put("callId", record.getId());
            payload.put("assistantId", assistantId);
            payload.put("userId", userId);
            webhookService.dispatch(WebhookDelivery.EVENT_CALL_CONNECTED, appId, payload);
        }

        logger.info("通话记录已创建，会话ID:{}，通话ID:{}", sessionId, record.getId());
        return record.getId();
    }

    /**
     * 结束通话记录（更新状态/时长/消息数/失败原因），OpenAPI 语音会话追加投递 call.completed Webhook
     *
     * <p>结算失败重试一次：这条更新一旦没落库，记录会永久停在"进行中"，时长与消息数就此丢失。
     * 仍失败时点名报出（不吞），由手册 6.6 的口径解释它对外部数字的影响。
     */
    private void finishCallRecord(String sessionId, int status, int messageCount, String failReason) {
        String callId = sessionCallRecordMap.remove(sessionId);
        if (!StringUtils.hasText(callId)) {
            return;
        }
        CallRecord record = null;
        try {
            record = callRecordService.getById(callId);
        } catch (Exception e) {
            logger.error("读取通话记录失败，通话ID:{}", callId, e);
        }
        if (record == null) {
            logger.error("通话记录不存在，无法结算，通话ID:{}（本通时长与消息数未落库）", callId);
            return;
        }
        record.setStatus(status);
        record.setEndedAt(LocalDateTime.now());
        record.setMessageCount(messageCount);
        record.setFailReason(failReason);
        if (record.getStartedAt() != null) {
            long seconds = Duration.between(record.getStartedAt(), record.getEndedAt()).getSeconds();
            record.setDurationSec((int) Math.max(seconds, 0));
        }
        settleCallRecord(record, callId, sessionId, status, messageCount);
    }

    /**
     * 结算落库：一次即时重试仍不够，就把"这一通没结算成"点名报出来，不让它停在无人知晓的进行中。
     */
    private void settleCallRecord(CallRecord record, String callId, String sessionId, int status, int messageCount) {
        try {
            callRecordService.update(record);
        } catch (Exception first) {
            logger.warn("更新通话记录失败，即时重试一次，通话ID:{}", callId, first);
            try {
                callRecordService.update(record);
            } catch (Exception second) {
                logger.error("通话记录结算两次均失败，通话ID:{}，状态:{}，消息数:{}（本通时长与消息数未落库，"
                        + "该记录在库中仍停在进行中）", callId, status, messageCount, second);
                return;
            }
        }

        // P2-17：OpenAPI 语音会话（有 appId）通话结束投递 Webhook；内部会话无 appId 跳过
        String appId = sessionAppIdMap.remove(sessionId);
        if (StringUtils.hasText(appId)) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("callId", callId);
            payload.put("assistantId", record.getAssistantId());
            payload.put("userId", record.getUserId());
            payload.put("status", status);
            payload.put("durationSec", record.getDurationSec() == null ? 0 : record.getDurationSec());
            payload.put("messageCount", messageCount);
            payload.put("recording", StringUtils.hasText(record.getRecordingName()));
            webhookService.dispatch(WebhookDelivery.EVENT_CALL_COMPLETED, appId, payload);
        }

        logger.info("通话记录已更新，通话ID:{}，状态:{}，消息数:{}", callId, status, messageCount);
    }

    /**
     * 处理语音识别结果 ASR
     */
    private void handleAsrResult(WebSocketSession session, String asrText) {
        if (asrText == null || asrText.isBlank()) {
            return;
        }
        String sessionId = session.getId();
        // 每轮开始复核：进行中的通话不该比一次握手活得更久（候选 ㉚）
        if (!openApiVoiceStillAllowed(session)) {
            logger.warn("开放语音本轮终止：会话ID:{} 的应用已不再具备语音能力", sessionId);
            sendMessage(session, MSG_TYPE_QUERY_END,
                    Map.of(FIELD_MESSAGE, "抱歉，本应用的语音能力已关闭，通话即将结束。", "status", "error"));
            closeSession(session);
            return;
        }
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

        // P2-19：语音配额逐条拦截（单日消息量超限时终止本轮，不发流）
        String userId = (String) session.getAttributes().get(SESSION_ATTR_USER_ID);
        String voice = sessionVoiceMap.get(sessionId);
        // 时长配额逐轮复核（C-126）：进行中的通话在 call_records 里 durationSec 恒为 0，
        // 只在发起前判一次的话，一整通超长通话可以整轮穿透日上限。
        // 排在消息配额之前：被时长拒掉的那一轮不该再烧掉一条消息额度。
        try {
            quotaService.checkOngoingCallSec(userId, sessionCallRecordMap.get(sessionId));
        } catch (QuotaExceededException e) {
            logger.warn("语音通话单日时长已达上限，终止通话，会话ID:{}，{}", sessionId, e.getMessage());
            sendMessage(session, MSG_TYPE_QUERY_END,
                    Map.of(FIELD_MESSAGE, e.getMessage(), "status", "error"));
            rustPBXService.sendTTS(rustpbxSessionId, "抱歉，本日通话时长已达上限，通话即将结束。", voice);
            closeSession(session);
            return;
        }
        try {
            quotaService.checkSendMessage(userId);
        } catch (QuotaExceededException e) {
            sendMessage(session, MSG_TYPE_QUERY_END,
                    Map.of(FIELD_MESSAGE, e.getMessage(), "status", "error"));
            rustPBXService.sendTTS(rustpbxSessionId, "抱歉，单日消息量已达上限，请明日再试。", voice);
            return;
        }

        // 终止上一轮未结束的流式请求
        Disposable oldSub = activeSubscriptions.get(sessionId);
        if (oldSub != null && !oldSub.isDisposed()) {
            oldSub.dispose();
        }

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
        // 区分正常挂断与异常中断：非正常关闭码标记为中断，并把中断原因一起落进通话记录
        if (isNormalClose(status)) {
            releaseSessionResource(sessionId, CallRecord.STATUS_ENDED, null);
        } else {
            releaseSessionResource(sessionId, CallRecord.STATUS_INTERRUPTED,
                    "连接异常断开（关闭码 " + (status == null ? "无" : String.valueOf(status.getCode())) + "）");
        }
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
        releaseSessionResource(sessionId, CallRecord.STATUS_ENDED, null);
    }

    /**
     * 统一释放会话资源
     *
     * @param sessionId  会话ID
     * @param endStatus  通话结束状态（正常结束 / 中断）
     * @param failReason 非正常结束的原因，随通话记录落库；正常结束传 null
     */
    private void releaseSessionResource(String sessionId, int endStatus, String failReason) {
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
        // 落库本通新增的对话记录（与文本、开放通道共用同一个落库单点）
        String assistantId = sessionAssistantMap.remove(sessionId);
        ChatService chatService = sessionChatServiceMap.remove(sessionId);
        int messageCount = 0;
        if (assistantId != null && chatService != null) {
            List<Record> pending = chatService.drainPendingRecords();
            if (!pending.isEmpty()) {
                ConversationRecordWriter.Result result = recordWriter.persist(
                        pending, assistantId, null, sessionCallRecordMap.get(sessionId));
                messageCount = result.saved();
                if (result.hasFailure()) {
                    logger.error("通话对话记录落库有失败，会话ID:{}，助手ID:{}，已保存 {} 条、失败 {} 条",
                            sessionId, assistantId, result.saved(), result.failed());
                }
            }
        }
        // 结束通话记录
        finishCallRecord(sessionId, endStatus, messageCount, failReason);
        sessionVoiceMap.remove(sessionId);
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
     * 开放语音会话的资格复核（候选 ㉚）：能力与启用状态在握手之后可被管理侧变更，
     * 而一次通话可以持续很久——只在握手判一次等于"吊销要等对方自己断开才生效"。
     * 内部会话（无 appId）不查库，返回放行。
     */
    private boolean openApiVoiceStillAllowed(WebSocketSession session) {
        String appId = (String) session.getAttributes().get(SESSION_ATTR_APP_ID);
        return !StringUtils.hasText(appId) || apiAppService.accessGranted(appId, ApiApp.SCOPE_VOICE);
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
