package com.leyon.backend.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.common.QuotaExceededException;
import com.leyon.backend.entity.ApiApp;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.CallRecord;
import com.leyon.backend.service.ApiAppService;
import com.leyon.backend.service.AssistantPolicy;
import com.leyon.backend.service.AssistantService;
import com.leyon.backend.service.CallRecordService;
import com.leyon.backend.service.KnowledgeBaseService;
import com.leyon.backend.service.KnowledgeProvider;
import com.leyon.backend.service.ModelAdapter;
import com.leyon.backend.service.ModelCatalog;
import com.leyon.backend.service.OrgService;
import com.leyon.backend.service.QuotaService;
import com.leyon.backend.service.RecordService;
import com.leyon.backend.service.RustPBXService;
import com.leyon.backend.service.WebhookService;
import com.leyon.backend.task.UnauthenticatedSocketReaper;
import com.leyon.backend.tool.ToolRegistry;
import com.leyon.backend.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 语音信令处理器单元测试
 * 覆盖：offer 的助手ID来自握手 URL 路径段（消息体传值被忽略）、路径缺失助手ID时拒绝、
 *       助手知识库按通话者可见范围收敛
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VoiceSignalingHandlerTest {

    @Mock
    private RustPBXService rustPBXService;
    @Mock
    private AssistantService assistantService;
    @Mock
    private ModelAdapter modelAdapter;
    @Mock
    private KnowledgeProvider knowledgeProvider;
    @Mock
    private RecordService recordService;
    @Mock
    private CallRecordService callRecordService;
    @Mock
    private OrgService orgService;
    @Mock
    private QuotaService quotaService;
    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private WebhookService webhookService;
    @Mock
    private ApiAppService apiAppService;
    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private UnauthenticatedSocketReaper socketReaper;
    @Mock
    private WebSocketSession session;

    private VoiceSignalingHandler handler;
    private final Map<String, Object> attributes = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        handler = new VoiceSignalingHandler(rustPBXService, assistantService, modelAdapter, knowledgeProvider,
                recordService, callRecordService, orgService, quotaService, knowledgeBaseService,
                webhookService, apiAppService, new ObjectMapper(), toolRegistry, jwtUtil, socketReaper,
                new AssistantPolicy(new ModelCatalog()));
        when(session.getId()).thenReturn("ws-1");
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(attributes);
        when(recordService.listByAssistantIdLimit(any(), anyInt())).thenReturn(List.of());
        when(rustPBXService.connectToRustPBX(any(), any(), any(), any(), any(), any())).thenReturn("gw-1");
    }

    private void connectViaPath(String path) {
        when(session.getUri()).thenReturn(URI.create(path));
        // 握手阶段已认证（内部 /ws-voice 与开放 /api/open/ws-voice 均如此注入属主身份）
        attributes.put("userId", "u1");
        handler.afterConnectionEstablished(session);
    }

    private Assistant personalAssistant(String knowledgeIds) {
        Assistant assistant = new Assistant();
        assistant.setId("a1");
        assistant.setUserId("u1");
        assistant.setVoice("voice-1");
        assistant.setKnowledgeIds(knowledgeIds);
        return assistant;
    }

    @Test
    void offer_resolvesAssistantIdFromUrlPath() {
        connectViaPath("ws://localhost/ws-voice/a1");
        when(assistantService.getById("a1")).thenReturn(personalAssistant(null));

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"offer\",\"sdp\":\"offer-sdp\"}"));

        verify(assistantService).getById("a1");
        verify(rustPBXService).connectToRustPBX(eq("offer-sdp"), eq("a1"), eq("voice-1"), any(), any(), any());
    }

    @Test
    void offer_ignoresAssistantIdFromMessageBody() {
        connectViaPath("ws://localhost/ws-voice/a1");
        when(assistantService.getById("a1")).thenReturn(personalAssistant(null));

        handler.handleTextMessage(session,
                new TextMessage("{\"type\":\"offer\",\"sdp\":\"offer-sdp\",\"assistantId\":\"a-foreign\"}"));

        verify(assistantService).getById("a1");
        verify(assistantService, never()).getById("a-foreign");
        verify(rustPBXService).connectToRustPBX(eq("offer-sdp"), eq("a1"), any(), any(), any(), any());
    }

    @Test
    void offer_withoutAssistantIdInPath_isRejected() {
        connectViaPath("ws://localhost/ws-voice/");

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"offer\",\"sdp\":\"offer-sdp\"}"));

        verifyNoInteractions(rustPBXService);
    }

    @Test
    void offer_dropsKnowledgeDatasetsInvisibleToCaller() {
        connectViaPath("ws://localhost/ws-voice/a1");
        when(assistantService.getById("a1")).thenReturn(personalAssistant("[\"d1\",\"d2\"]"));
        when(knowledgeBaseService.retainVisibleDatasetIds(any(), eq("u1"))).thenReturn(List.of("d1"));

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"offer\",\"sdp\":\"offer-sdp\"}"));

        verify(knowledgeBaseService).retainVisibleDatasetIds(eq(List.of("d1", "d2")), eq("u1"));
    }

    /** 开放语音会话：握手拦截器除属主外还注入 appId，据此区分"第三方应用接入" */
    private void connectOpenApiSession() {
        when(session.getUri()).thenReturn(URI.create("ws://localhost/api/open/ws-voice/a1"));
        attributes.put("userId", "u1");
        attributes.put("appId", "app-1");
        handler.afterConnectionEstablished(session);
    }

    @Test
    void offer_openApiSession_refusedWhenVoiceScopeRevoked() throws Exception {
        connectOpenApiSession();
        when(apiAppService.accessGranted("app-1", ApiApp.SCOPE_VOICE)).thenReturn(false);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"offer\",\"sdp\":\"offer-sdp\"}"));

        verifyNoInteractions(rustPBXService);
        verifyNoInteractions(assistantService);
        verify(session).close();
    }

    @Test
    void offer_openApiSession_proceedsWhileScopeStillGranted() throws Exception {
        connectOpenApiSession();
        when(apiAppService.accessGranted("app-1", ApiApp.SCOPE_VOICE)).thenReturn(true);
        when(assistantService.getById("a1")).thenReturn(personalAssistant(null));

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"offer\",\"sdp\":\"offer-sdp\"}"));

        verify(rustPBXService).connectToRustPBX(eq("offer-sdp"), eq("a1"), any(), any(), any(), any());
        verify(session, never()).close();
    }

    @Test
    void offer_internalSession_neverConsultsApiApp() {
        connectViaPath("ws://localhost/ws-voice/a1");
        when(assistantService.getById("a1")).thenReturn(personalAssistant(null));

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"offer\",\"sdp\":\"offer-sdp\"}"));

        verifyNoInteractions(apiAppService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void asrRound_terminatedWhenScopeRevokedMidCall() throws Exception {
        connectOpenApiSession();
        AtomicBoolean granted = new AtomicBoolean(true);
        when(apiAppService.accessGranted("app-1", ApiApp.SCOPE_VOICE)).thenAnswer(inv -> granted.get());
        when(assistantService.getById("a1")).thenReturn(personalAssistant(null));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"offer\",\"sdp\":\"offer-sdp\"}"));

        ArgumentCaptor<Consumer<String>> asrCallback = ArgumentCaptor.forClass(Consumer.class);
        verify(rustPBXService).connectToRustPBX(any(), any(), any(), any(), asrCallback.capture(), any());

        granted.set(false);
        asrCallback.getValue().accept("现在几点了");

        verify(quotaService, never()).checkSendMessage(any());
        verify(rustPBXService, never()).sendTTS(any(), any(), any());
        verify(session).close();
    }

    /**
     * 时长配额在回合边界复核（C-126）：进行中的通话不进已结算时长，发起前那一次判定管不了一整通超长通话。
     * 断言三件事：复核带上了本通通话ID（否则算不出"本通已活秒数"）、被拒这一轮不烧消息配额、
     * 用户是被播报＋挂断而不是只收到一条读不到的错误帧。
     */
    @Test
    @SuppressWarnings("unchecked")
    void asrRound_terminatedWhenDailyCallSecExhaustedMidCall() throws Exception {
        connectOpenApiSession();
        when(apiAppService.accessGranted("app-1", ApiApp.SCOPE_VOICE)).thenReturn(true);
        when(assistantService.getById("a1")).thenReturn(personalAssistant(null));
        when(callRecordService.create(any(CallRecord.class))).thenAnswer(inv -> {
            CallRecord created = inv.getArgument(0);
            created.setId("c1");
            return created;
        });
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"offer\",\"sdp\":\"offer-sdp\"}"));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"webrtc_connected\"}"));

        ArgumentCaptor<Consumer<String>> asrCallback = ArgumentCaptor.forClass(Consumer.class);
        verify(rustPBXService).connectToRustPBX(any(), any(), any(), any(), asrCallback.capture(), any());

        doThrow(new QuotaExceededException("单日通话时长已达上限（1 分钟），请明日再试"))
                .when(quotaService).checkOngoingCallSec("u1", "c1");

        asrCallback.getValue().accept("现在几点了");

        verify(quotaService).checkOngoingCallSec("u1", "c1");
        verify(quotaService, never()).checkSendMessage(any());
        verify(rustPBXService).sendTTS(eq("gw-1"), contains("通话时长"), any());
        verify(session).close();
    }
}
