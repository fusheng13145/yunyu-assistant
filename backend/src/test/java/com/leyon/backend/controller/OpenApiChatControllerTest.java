package com.leyon.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.common.ForbiddenException;
import com.leyon.backend.common.QuotaExceededException;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.Record;
import com.leyon.backend.entity.Session;
import com.leyon.backend.interceptor.OpenApiAuthInterceptor;
import com.leyon.backend.service.AssistantService;
import com.leyon.backend.service.KnowledgeProvider;
import com.leyon.backend.service.ModelAdapter;
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
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private OpenApiChatController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = new OpenApiChatController(assistantService, orgService, quotaService,
                sessionService, recordService, webhookService, modelAdapter, knowledgeProvider, new ObjectMapper(), toolRegistry);
        request = org.mockito.Mockito.mock(HttpServletRequest.class);
        lenient().when(request.getAttribute(OpenApiAuthInterceptor.ATTR_USER_ID)).thenReturn("u-owner");
        lenient().when(toolRegistry.getAllToolCallbacks()).thenReturn(List.of());
        lenient().when(recordService.listBySessionIdLimit(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
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
}