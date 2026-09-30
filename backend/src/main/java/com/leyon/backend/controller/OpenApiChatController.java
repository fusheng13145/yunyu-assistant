package com.leyon.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.common.ForbiddenException;
import com.leyon.backend.common.QuotaExceededException;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.Record;
import com.leyon.backend.entity.Session;
import com.leyon.backend.entity.WebhookDelivery;
import com.leyon.backend.service.AssistantPolicy;
import com.leyon.backend.service.AssistantService;
import com.leyon.backend.service.ChatService;
import com.leyon.backend.service.ConversationRecordWriter;
import com.leyon.backend.service.KnowledgeBaseService;
import com.leyon.backend.service.KnowledgeProvider;
import com.leyon.backend.service.ModelAdapter;
import com.leyon.backend.service.OrgService;
import com.leyon.backend.service.QuotaService;
import com.leyon.backend.service.RecordService;
import com.leyon.backend.service.SessionService;
import com.leyon.backend.service.WebhookService;
import com.leyon.backend.tool.ToolRegistry;
import com.leyon.backend.interceptor.OpenApiAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 开放 OpenAPI 接口（P2-10 开放 OpenAPI；v2.15 升级为多轮会话）
 * 经 /api/open/** 路由，由 OpenApiAuthInterceptor 以 X-API-Key 鉴权（与 JWT 通道完全隔离）
 * 文本对话流式输出（SSE），复用 ChatService，支持会话续聊：
 * - 首轮未携带 sessionId 时自动创建会话，对话落库后由 autoTitle 生成标题；
 * - 携带 sessionId 时校验归属并加载历史注入上下文（多轮）；结束后回传 sessionId 供下一轮接力；
 * - 落库 records（按 session 归集），第三方消息量计入其属主配额。
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/open")
public class OpenApiChatController {

    private static final Logger logger = LoggerFactory.getLogger(OpenApiChatController.class);

    /** 加载历史的最大条数（与文本对话默认一致，限流上下文防溢出） */
    private static final int HISTORY_LIMIT = 50;

    private final AssistantService assistantService;
    private final OrgService orgService;
    private final QuotaService quotaService;
    private final SessionService sessionService;
    private final RecordService recordService;
    private final WebhookService webhookService;
    private final ModelAdapter modelAdapter;
    private final KnowledgeProvider knowledgeProvider;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final AssistantPolicy assistantPolicy;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ConversationRecordWriter recordWriter;

    public OpenApiChatController(AssistantService assistantService,
                                 OrgService orgService,
                                 QuotaService quotaService,
                                 SessionService sessionService,
                                 RecordService recordService,
                                 WebhookService webhookService,
                                 ModelAdapter modelAdapter,
                                 KnowledgeProvider knowledgeProvider,
                                 ObjectMapper objectMapper,
                                 ToolRegistry toolRegistry,
                                 AssistantPolicy assistantPolicy,
                                 KnowledgeBaseService knowledgeBaseService,
                                 ConversationRecordWriter recordWriter) {
        this.assistantService = assistantService;
        this.orgService = orgService;
        this.quotaService = quotaService;
        this.sessionService = sessionService;
        this.recordService = recordService;
        this.webhookService = webhookService;
        this.modelAdapter = modelAdapter;
        this.knowledgeProvider = knowledgeProvider;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.assistantPolicy = assistantPolicy;
        this.knowledgeBaseService = knowledgeBaseService;
        this.recordWriter = recordWriter;
    }

    /**
     * 文本对话流式接口（SSE，多轮会话）
     * body: { assistantId, message, sessionId? }（sessionId 可选：缺省自动建会话，携带则续聊）
     * 输出：data: {"segment": "...", "streamEnd": false} … 结束时 data: {"segment":"","streamEnd":true,"message",...,"sessionId":"..."}
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> chat(@RequestBody Map<String, String> body,
                                                          HttpServletRequest request) {
        String assistantId = body == null ? null : body.get("assistantId");
        String message = body == null ? null : body.get("message");
        String sessionId = body == null ? null : body.get("sessionId");
        String userId = (String) request.getAttribute(OpenApiAuthInterceptor.ATTR_USER_ID);
        String appId = (String) request.getAttribute(OpenApiAuthInterceptor.ATTR_APP_ID);

        // 参数校验
        if (!StringUtils.hasText(assistantId)) {
            return Flux.just(toErrorEvent("assistantId 不能为空"));
        }
        if (!StringUtils.hasText(message)) {
            return Flux.just(toErrorEvent("message 不能为空"));
        }
        // 单条长度上限与文本 WS 同源（ChatService.MAX_INPUT_CHARS）：配额按条数计量，不限长则一条即可打穿成本
        if (message.length() > ChatService.MAX_INPUT_CHARS) {
            return Flux.just(toErrorEvent("message 过长（最多 " + ChatService.MAX_INPUT_CHARS + " 字）"));
        }
        // 配额校验（计入第三方属主身份）
        try {
            quotaService.checkSendMessage(userId);
        } catch (QuotaExceededException e) {
            return Flux.just(toErrorEvent(e.getMessage()));
        }

        // 助手归属校验（个人数据按 userId；组织数据按成员 viewer 以上可读/使用）
        Assistant assistant = assistantService.getById(assistantId);
        if (assistant == null) {
            return Flux.just(toErrorEvent("助手不存在"));
        }
        try {
            requireRead(assistant, userId);
        } catch (ForbiddenException e) {
            return Flux.just(toErrorEvent(e.getMessage()));
        }

        // 会话解析：携带 sessionId 校验归属与助手匹配；未携带自动创建（首轮）
        String effectiveSessionId;
        if (StringUtils.hasText(sessionId)) {
            Session bizSession = sessionService.getOwned(sessionId, userId);
            if (bizSession == null) {
                return Flux.just(toErrorEvent("会话不存在或无访问权限"));
            }
            if (!assistantId.equals(bizSession.getAssistantId())) {
                return Flux.just(toErrorEvent("会话与助手不匹配"));
            }
            effectiveSessionId = bizSession.getId();
        } else {
            Session created = sessionService.create(userId, assistantId, null, assistant.getOrgId());
            effectiveSessionId = created.getId();
        }
        final String bizSessionId = effectiveSessionId;

        // 复用 ChatService 流式逻辑（与 WS 通道同装配），加载历史注入上下文实现多轮
        // 知识库范围与两条内部通道同一判据：按**调用方**（应用属主）可见集求交，不因为"整轮都是属主身份"就跳过
        List<String> knowledgeIds = knowledgeBaseService.retainVisibleDatasetIds(
                parseKnowledgeIds(assistant.getKnowledgeIds()), userId);
        AssistantPolicy.Runtime runtime = assistantPolicy.runtime(assistant);
        ChatService chatService = new ChatService(
                modelAdapter, knowledgeProvider, objectMapper,
                runtime.personality(), knowledgeIds,
                toolRegistry.resolveToolCallbacks(assistant.getToolList())
        );
        chatService.setModelParams(runtime.model(), runtime.temperature(), runtime.maxTokens());
        List<Record> history = recordService.listBySessionIdLimit(bizSessionId, HISTORY_LIMIT);
        if (!history.isEmpty()) {
            chatService.loadChatHistory(history);
        }

        return chatService.chatStream(message)
                // 结束帧回传 sessionId（供第三方下一轮接力）
                .map(chunk -> {
                    if (Boolean.TRUE.equals(chunk.get("streamEnd"))) {
                        chunk.put("sessionId", bizSessionId);
                    }
                    return ServerSentEvent.<Map<String, Object>>builder().data(chunk).build();
                })
                // 流结束/异常/取消后落库本轮记录（含自动标题）并投递 Webhook，保证多轮上下文持久
                .doFinally(signalType -> {
                    List<Record> pending = chatService.drainPendingRecords();
                    if (!pending.isEmpty()) {
                        ConversationRecordWriter.Result result =
                                recordWriter.persist(pending, assistantId, bizSessionId, null);
                        if (result.hasFailure()) {
                            // SSE 已收尾，无法再向第三方回传失败帧；对外契约的失败出口属接口变更，见手册 6.6
                            logger.error("OpenAPI 落库对话记录有失败，助手ID:{}，会话ID:{}，已保存 {} 条、失败 {} 条",
                                    assistantId, bizSessionId, result.saved(), result.failed());
                        }
                    }
                    dispatchMessageCompleted(appId, assistantId, message, bizSessionId);
                });
    }

    /**
     * 投递 message.completed Webhook（第三方应用配置 webhook_url 后接收）
     */
    private void dispatchMessageCompleted(String appId, String assistantId, String message, String sessionId) {
        if (!StringUtils.hasText(appId)) {
            return;
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("assistantId", assistantId);
        payload.put("message", message);
        payload.put("sessionId", sessionId);
        webhookService.dispatch(WebhookDelivery.EVENT_MESSAGE_COMPLETED, appId, payload);
    }

    /**
     * 读取校验：个人资源按 userId；组织资源需为组织成员（viewer 以上）
     */
    private void requireRead(Assistant assistant, String userId) {
        if (StringUtils.hasText(assistant.getOrgId())) {
            if (!orgService.isMember(assistant.getOrgId(), userId)) {
                throw new ForbiddenException("无权访问该助手");
            }
        } else if (!userId.equals(assistant.getUserId())) {
            throw new ForbiddenException("无权访问该助手");
        }
    }

    /**
     * 解析助手的知识库ID JSON 字符串（与 ChatWebSocketHandler. parseKnowledgeIds 语义一致）
     */
    private List<String> parseKnowledgeIds(String knowledgeIdsStr) {
        if (!StringUtils.hasText(knowledgeIdsStr) || "[]".equals(knowledgeIdsStr.trim())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(knowledgeIdsStr,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                    });
        } catch (Exception e) {
            return List.of();
        }
    }

    private ServerSentEvent<Map<String, Object>> toErrorEvent(String message) {
        return ServerSentEvent.<Map<String, Object>>builder()
                .data(Map.of("streamEnd", true, "error", message))
                .build();
    }
}