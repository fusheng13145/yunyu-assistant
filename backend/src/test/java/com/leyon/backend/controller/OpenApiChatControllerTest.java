package com.leyon.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.common.ForbiddenException;
import com.leyon.backend.common.QuotaExceededException;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.Record;
import com.leyon.backend.entity.Session;
import com.leyon.backend.interceptor.OpenApiAuthInterceptor;
import com.leyon.backend.service.AssistantPolicy;
import com.leyon.backend.service.AssistantService;
import com.leyon.backend.service.ChatService;
import com.leyon.backend.service.ConversationRecordWriter;
import com.leyon.backend.service.KnowledgeBaseService;
import com.leyon.backend.service.KnowledgeProvider;
import com.leyon.backend.service.ModelAdapter;
import com.leyon.backend.service.ModelCatalog;
import com.leyon.backend.service.OrgService;
import com.leyon.backend.service.QuotaService;
import com.leyon.backend.service.RecordService;
import com.leyon.backend.service.SessionService;
import com.leyon.backend.service.WebhookService;
import com.leyon.backend.tool.ToolRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.leyon.backend.service.MemoryToolService;
import com.leyon.backend.service.UserMemoryService;

/**
 * 开放 OpenAPI 文本对话控制器单元测试（P2-10 开放 OpenAPI；v2.15 多轮会话）
 * 覆盖：参数校验、配额超限拒绝、助手归属校验（个人/组织）、会话解析（首轮建会话/续聊/越权/不匹配）、落库
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenApiChatControllerTest {

    @Mock
    private AssistantService assistantService;
    @Mock
    private OrgService orgService;
    @Mock
    private QuotaService quotaService;
    @Mock
    private UserMemoryService userMemoryService;
    @Mock
    private SessionService sessionService;
    @Mock
    private RecordService recordService;
    @Mock
    private WebhookService webhookService;
    @Mock
    private ModelAdapter modelAdapter;
    @Mock
    private KnowledgeProvider knowledgeProvider;
    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private MemoryToolService memoryToolService;
    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private ConversationRecordWriter recordWriter;

    /** 每轮检索实际传入 KnowledgeProvider 的数据集ID（由 setUp 里的 thenAnswer 记录） */
    private final List<List<String>> queriedDatasets = new ArrayList<>();

    private OpenApiChatController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = new OpenApiChatController(assistantService, orgService, quotaService,
                sessionService, recordService, webhookService, modelAdapter, knowledgeProvider, new ObjectMapper(),
                toolRegistry, new com.leyon.backend.service.ToolQuotaGuard(quotaService, 200),
                memoryToolService, userMemoryService,
                new AssistantPolicy(new ModelCatalog()), knowledgeBaseService, recordWriter);
        request = org.mockito.Mockito.mock(HttpServletRequest.class);
        lenient().when(request.getAttribute(OpenApiAuthInterceptor.ATTR_USER_ID)).thenReturn("u-owner");
        lenient().when(toolRegistry.resolveToolCallbacks(any())).thenReturn(List.of());
        lenient().when(recordService.listBySessionIdLimit(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        // 记录检索真正拿到的数据集ID（断言"可见子集才进检索"要看下游读数，不能只看桩被调用几次）
        lenient().when(knowledgeProvider.queryKnowledgeBaseWithDetail(any(), any())).thenAnswer(invocation -> {
            queriedDatasets.add(new ArrayList<>(invocation.getArgument(1)));
            return KnowledgeProvider.KnowledgeHit.empty();
        });
    }

    private Assistant personalAssistant() {
        Assistant a = new Assistant();
        a.setId("a1");
        a.setUserId("u-owner");
        a.setPersonality("测试人设");
        return a;
    }

    private Assistant orgAssistant(String orgId) {
        Assistant a = new Assistant();
        a.setId("a1");
        a.setUserId("u-other");
        a.setOrgId(orgId);
        a.setPersonality("测试人设");
        return a;
    }

    private Session ownedSession(String id, String assistantId, String userId) {
        Session s = new Session();
        s.setId(id);
        s.setUserId(userId);
        s.setAssistantId(assistantId);
        return s;
    }

    @Test
    void chat_missingAssistantId_returnsErrorEvent() {
        Flux<ServerSentEvent<Map<String, Object>>> flux = controller.chat(Map.of("message", "你好"), request);
        StepVerifier.create(flux)
                .assertNext(ev -> assertThat(StringUtils.hasText((String) ev.data().get("error"))).isTrue())
                .expectComplete()
                .verify();
    }

    @Test
    void chat_quotaExceeded_returnsErrorEvent() {
        org.mockito.Mockito.doThrow(new QuotaExceededException("消息配额超限"))
                .when(quotaService).checkSendMessage("u-owner");
        Flux<ServerSentEvent<Map<String, Object>>> flux =
                controller.chat(Map.of("assistantId", "a1", "message", "你好"), request);
        StepVerifier.create(flux)
                .assertNext(ev -> assertThat((String) ev.data().get("error")).contains("配额"))
                .expectComplete()
                .verify();
    }

    @Test
    void chat_oversizedMessage_returnsErrorEventBeforeQuota() {
        // 与文本 WS 同源的(CONTENT.length 上限：配额按条数计量，单条不限长即可打穿成本
        String oversized = "啊".repeat(ChatService.MAX_INPUT_CHARS + 1);
        Flux<ServerSentEvent<Map<String, Object>>> flux =
                controller.chat(Map.of("assistantId", "a1", "message", oversized), request);
        StepVerifier.create(flux)
                .assertNext(ev -> assertThat((String) ev.data().get("error")).contains("过长"))
                .expectComplete()
                .verify();
        org.mockito.Mockito.verify(quotaService, org.mockito.Mockito.never()).checkSendMessage(any());
    }

    @Test
    void chat_assistantNotFound_returnsErrorEvent() {
        when(assistantService.getById("a1")).thenReturn(null);
        Flux<ServerSentEvent<Map<String, Object>>> flux =
                controller.chat(Map.of("assistantId", "a1", "message", "你好"), request);
        StepVerifier.create(flux)
                .assertNext(ev -> assertThat((String) ev.data().get("error")).contains("助手不存在"))
                .expectComplete()
                .verify();
    }

    @Test
    void chat_orgAssistant_nonMemberRejected() {
        when(assistantService.getById("a1")).thenReturn(orgAssistant("org1"));
        when(orgService.isMember("org1", "u-owner")).thenReturn(false);
        Flux<ServerSentEvent<Map<String, Object>>> flux =
                controller.chat(Map.of("assistantId", "a1", "message", "你好"), request);
        StepVerifier.create(flux)
                .assertNext(ev -> assertThat((String) ev.data().get("error")).contains("无权"))
                .expectComplete()
                .verify();
    }

    // ===================== v2.15 多轮会话：会话解析 =====================

    private void mockEmptyModelStream() {
        // 模拟模型流式无输出（空流）：chatStream 正常结束，走完会话落库路径而不真正调用 LLM
        when(modelAdapter.stream(any())).thenReturn(Flux.empty());
    }

    @Test
    void chat_firstTurnWithoutSessionId_createsSession() {
        mockEmptyModelStream();
        when(assistantService.getById("a1")).thenReturn(personalAssistant());
        when(sessionService.create(eq("u-owner"), eq("a1"), org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(ownedSession("s-new", "a1", "u-owner"));
        Flux<ServerSentEvent<Map<String, Object>>> flux =
                controller.chat(Map.of("assistantId", "a1", "message", "你好"), request);
        StepVerifier.create(flux)
                .assertNext(ev -> assertThat(ev.data().get("streamEnd")).isEqualTo(Boolean.TRUE))
                .expectComplete()
                .verify();
        // 首轮自动创建会话（标题/org 均传 null）
        verify(sessionService).create(eq("u-owner"), eq("a1"), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void chat_withValidSessionId_continuesTurn() {
        mockEmptyModelStream();
        when(assistantService.getById("a1")).thenReturn(personalAssistant());
        when(sessionService.getOwned("s1", "u-owner")).thenReturn(ownedSession("s1", "a1", "u-owner"));
        Flux<ServerSentEvent<Map<String, Object>>> flux =
                controller.chat(Map.of("assistantId", "a1", "message", "你好", "sessionId", "s1"), request);
        StepVerifier.create(flux)
                .assertNext(ev -> assertThat(ev.data().get("streamEnd")).isEqualTo(Boolean.TRUE))
                .expectComplete()
                .verify();
        // 续聊不重建会话
        verify(sessionService, org.mockito.Mockito.never()).create(any(), any(), any(), any());
    }

    @Test
    void chat_withForeignSessionId_rejected() {
        when(assistantService.getById("a1")).thenReturn(personalAssistant());
        when(sessionService.getOwned("s1", "u-owner")).thenReturn(null);
        Flux<ServerSentEvent<Map<String, Object>>> flux =
                controller.chat(Map.of("assistantId", "a1", "message", "你好", "sessionId", "s1"), request);
        StepVerifier.create(flux)
                .assertNext(ev -> assertThat((String) ev.data().get("error")).contains("会话不存在"))
                .expectComplete()
                .verify();
    }

    @Test
    void chat_sessionAssistantMismatch_rejected() {
        when(assistantService.getById("a1")).thenReturn(personalAssistant());
        when(sessionService.getOwned("s1", "u-owner")).thenReturn(ownedSession("s1", "a-other", "u-owner"));
        Flux<ServerSentEvent<Map<String, Object>>> flux =
                controller.chat(Map.of("assistantId", "a1", "message", "你好", "sessionId", "s1"), request);
        StepVerifier.create(flux)
                .assertNext(ev -> assertThat((String) ev.data().get("error")).contains("不匹配"))
                .expectComplete()
                .verify();
    }

    @Test
    void chat_orgAssistant_memberContinuesWithSession() {
        mockEmptyModelStream();
        when(assistantService.getById("a1")).thenReturn(orgAssistant("org1"));
        when(orgService.isMember("org1", "u-owner")).thenReturn(true);
        when(sessionService.getOwned("s1", "u-owner")).thenReturn(ownedSession("s1", "a1", "u-owner"));
        Flux<ServerSentEvent<Map<String, Object>>> flux =
                controller.chat(Map.of("assistantId", "a1", "message", "你好", "sessionId", "s1"), request);
        StepVerifier.create(flux)
                .assertNext(ev -> assertThat(ev.data().get("streamEnd")).isEqualTo(Boolean.TRUE))
                .expectComplete()
                .verify();
    }

    // ===================== ㊷ / C-125：开放通道的知识库可见性求交 =====================

    /** 组织助手（属主 u-other）携带属主的私有数据集：调用方 u-owner 看不见的项必须被收敛掉 */
    private Assistant orgAssistantWithDatasets(String... datasetIds) {
        Assistant a = orgAssistant("org1");
        a.setKnowledgeIdList(List.of(datasetIds));
        lenient().when(orgService.isMember("org1", "u-owner")).thenReturn(true);
        lenient().when(sessionService.getOwned("s1", "u-owner")).thenReturn(ownedSession("s1", "a1", "u-owner"));
        return a;
    }

    /** 走完一轮开放通道对话（模型侧空流），不断言事件内容；检索读数由 setUp 的记录桩采集 */
    private void runOpenApiTurn(Assistant assistant) {
        mockEmptyModelStream();
        when(assistantService.getById("a1")).thenReturn(assistant);
        StepVerifier.create(controller.chat(
                        Map.of("assistantId", "a1", "message", "你好", "sessionId", "s1"), request))
                .expectNextCount(1)
                .expectComplete()
                .verify();
    }

    @Test
    void chat_onlyVisibleDatasetsReachRetrieval() {
        when(knowledgeBaseService.retainVisibleDatasetIds(eq(List.of("ds-owner-private", "ds-shared")), eq("u-owner")))
                .thenReturn(List.of("ds-shared"));
        runOpenApiTurn(orgAssistantWithDatasets("ds-owner-private", "ds-shared"));
        assertThat(queriedDatasets).containsExactly(List.of("ds-shared"));
    }

    @Test
    void chat_visibilityJudgedAgainstCallerRatherThanAssistantOwner() {
        when(knowledgeBaseService.retainVisibleDatasetIds(any(), eq("u-owner")))
                .thenReturn(List.of("ds-shared"));
        runOpenApiTurn(orgAssistantWithDatasets("ds-owner-private", "ds-shared"));
        // 助手属主是 u-other，判定身份必须是发起调用的应用属主 u-owner（与配额/requireRead 同一口径）
        verify(knowledgeBaseService).retainVisibleDatasetIds(eq(List.of("ds-owner-private", "ds-shared")), eq("u-owner"));
        verify(knowledgeBaseService, never()).retainVisibleDatasetIds(any(), eq("u-other"));
    }

    @Test
    void chat_whenNoDatasetVisible_retrievalSkippedEntirely() {
        when(knowledgeBaseService.retainVisibleDatasetIds(any(), eq("u-owner"))).thenReturn(List.of());
        runOpenApiTurn(orgAssistantWithDatasets("ds-owner-private"));
        verify(knowledgeProvider, never()).queryKnowledgeBaseWithDetail(any(), any());
    }

    @Test
    void chat_assistantWithoutDatasets_skipsRetrieval() {
        runOpenApiTurn(personalAssistant());
        verify(knowledgeProvider, never()).queryKnowledgeBaseWithDetail(any(), any());
    }

    // ===================== v2.66 · C-133：三份复制落库收口为单点 =====================

    @Test
    void chat_answeredTurn_persistsTurnThroughWriter() {
        // 模型有输出的一轮：落库单点必须收到本轮记录，并带上业务会话ID（多轮上下文靠它）
        when(modelAdapter.stream(any())).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("回答"))))));
        when(assistantService.getById("a1")).thenReturn(personalAssistant());
        when(sessionService.getOwned("s1", "u-owner")).thenReturn(ownedSession("s1", "a1", "u-owner"));
        when(recordWriter.persist(any(), eq("a1"), eq("s1"),
                org.mockito.ArgumentMatchers.<String>isNull()))
                .thenReturn(new ConversationRecordWriter.Result(2, 0));

        StepVerifier.create(controller.chat(
                        Map.of("assistantId", "a1", "message", "你好", "sessionId", "s1"), request))
                .thenConsumeWhile(x -> true)
                .expectComplete()
                .verify();

        verify(recordWriter).persist(any(), eq("a1"), eq("s1"),
                org.mockito.ArgumentMatchers.<String>isNull());
    }

    @Test
    void chat_emptyModelStream_stillCompletesWithoutPersistCall() {
        // 与上一例成对：空流没有本轮内容可落，收尾仍要完整走完（落库调用不该凭空白调）
        mockEmptyModelStream();
        when(assistantService.getById("a1")).thenReturn(personalAssistant());
        when(sessionService.getOwned("s1", "u-owner")).thenReturn(ownedSession("s1", "a1", "u-owner"));

        StepVerifier.create(controller.chat(
                        Map.of("assistantId", "a1", "message", "你好", "sessionId", "s1"), request))
                .thenConsumeWhile(x -> true)
                .expectComplete()
                .verify();

        verify(recordWriter, never()).persist(any(), any(), any(), any());
    }
}