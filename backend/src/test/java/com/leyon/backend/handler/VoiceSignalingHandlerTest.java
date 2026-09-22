package com.leyon.backend.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.service.AssistantService;
import com.leyon.backend.service.CallRecordService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
    private ToolRegistry toolRegistry;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private WebSocketSession session;

    private VoiceSignalingHandler handler;
    private final Map<String, Object> attributes = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        handler = new VoiceSignalingHandler(rustPBXService, assistantService, modelAdapter, knowledgeProvider,
                recordService, callRecordService, orgService, quotaService, knowledgeBaseService,
                webhookService, new ObjectMapper(), toolRegistry, jwtUtil);
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
}
