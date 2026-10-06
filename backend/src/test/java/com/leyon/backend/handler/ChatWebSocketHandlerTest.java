package com.leyon.backend.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.Org;
import com.leyon.backend.entity.Record;
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
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 聊天 WebSocket 处理器单元测试
 * 覆盖：流式成功后下发带结束分片载荷的 query_end、异常收尾同样下发、
 *       逐轮落库与落库失败的 error 帧出口、selectedKbIds 按可见数据集收敛、人设回写需管理权限
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatWebSocketHandlerTest {

    @Mock
    private ModelAdapter modelAdapter;
    @Mock
    private KnowledgeProvider knowledgeProvider;
    @Mock
    private AssistantService assistantService;
    @Mock
    private RecordService recordService;
    @Mock
    private SessionService sessionService;
    @Mock
    private OrgService orgService;
    @Mock
    private QuotaService quotaService;
    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private ChatService chatService;
    @Mock
    private ConversationRecordWriter recordWriter;
    @Mock
    private com.leyon.backend.service.ToolQuotaGuard toolQuotaGuard;
    @Mock
    private WebSocketSession session;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Object> attributes = new HashMap<>();
    private ChatWebSocketHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        handler = new ChatWebSocketHandler(modelAdapter, knowledgeProvider, assistantService, recordService,
                sessionService, orgService, quotaService, knowledgeBaseService, objectMapper, jwtUtil, toolRegistry,
                new AssistantPolicy(new ModelCatalog()), recordWriter, toolQuotaGuard);
        when(session.getId()).thenReturn("ws-1");
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(attributes);
        attributes.put("userId", "u1");
        chatServices().put("ws-1", chatService);
        assistantIds().put("ws-1", "a1");
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, ChatService> chatServices() throws Exception {
        return (ConcurrentHashMap<String, ChatService>) field("chatServices");
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, String> assistantIds() throws Exception {
        return (ConcurrentHashMap<String, String>) field("sessionAssistantMap");
    }

    private Object field(String name) throws Exception {
        Field f = ChatWebSocketHandler.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(handler);
    }

    /** 收集服务端下发的消息（type -> data 节点） */
    private List<JsonNode> sentMessages() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().stream().map(m -> {
            try {
                return objectMapper.readTree(m.getPayload());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    private static Map<String, Object> segmentChunk(String text) {
        Map<String, Object> chunk = new HashMap<>();
        chunk.put("segment", text);
        chunk.put("streamEnd", false);
        return chunk;
    }

    private static Map<String, Object> endChunk(String message) {
        Map<String, Object> chunk = new HashMap<>();
        chunk.put("segment", "");
        chunk.put("streamEnd", true);
        chunk.put("message", message);
        chunk.put("costTime", 120L);
        chunk.put("knowledgebase", Map.of("docCount", 1, "docName", List.of("手册.pdf")));
        chunk.put("tokenUsage", Map.of("promptTokens", 10, "completionTokens", 20));
        return chunk;
    }

    @Test
    void chat_success_emitsQueryEndCarryingEndChunk() throws Exception {
        when(chatService.chatStream("你好")).thenReturn(
                Flux.just(segmentChunk("你"), segmentChunk("好"), endChunk("你好呀")));

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"chat\",\"content\":\"你好\"}"));

        List<JsonNode> messages = sentMessages();
        JsonNode queryEnd = messages.stream()
                .filter(m -> "query_end".equals(m.path("type").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("成功后应下发 query_end，实际: " + messages));
        assertThat(queryEnd.path("data").path("message").asText()).isEqualTo("你好呀");
        assertThat(queryEnd.path("data").path("costTime").asLong()).isEqualTo(120L);
        assertThat(queryEnd.path("data").path("tokenUsage").path("promptTokens").asInt()).isEqualTo(10);
        assertThat(messages.get(messages.size() - 1).path("type").asText()).isEqualTo("query_end");
    }

    @Test
    void chat_error_emitsQueryEndWithNonNullPayload() throws Exception {
        when(chatService.chatStream(anyString())).thenReturn(Flux.error(new RuntimeException("模型不可用")));

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"chat\",\"content\":\"你好\"}"));

        List<JsonNode> messages = sentMessages();
        JsonNode queryEnd = messages.stream()
                .filter(m -> "query_end".equals(m.path("type").asText()))
                .findFirst().orElseThrow();
        // 前端以 query_end.data 收尾打字态，载荷不得为 null（否则解析报错、气泡一直闪烁）
        assertThat(queryEnd.has("data")).isTrue();
        assertThat(queryEnd.path("data").path("streamEnd").asBoolean()).isTrue();
    }

    @Test
    void chat_error_surfacesErrorFrameBeforeClosingFrames() throws Exception {
        when(chatService.chatStream(anyString())).thenReturn(Flux.error(new RuntimeException("模型不可用")));

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"chat\",\"content\":\"你好\"}"));

        List<String> types = sentMessages().stream().map(m -> m.path("type").asText()).toList();
        int errorIdx = types.indexOf("error");
        // 只补收尾帧时，用户看到的是一段戛然而止的空白回答，失败与"模型就是这么短"无从分辨
        assertThat(errorIdx).as("上游失败必须下发 error 帧，实际: %s", types).isNotNegative();
        assertThat(types.indexOf("query_end")).as("error 帧要先于收尾帧: %s", types).isGreaterThan(errorIdx);
        assertThat(types.get(types.size() - 1)).isEqualTo("query_end");
    }

    @Test
    void chat_error_errorFrameDoesNotEchoUpstreamDetail() throws Exception {
        when(chatService.chatStream(anyString())).thenReturn(Flux.error(new RuntimeException("模型不可用")));

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"chat\",\"content\":\"你好\"}"));

        // 上游异常原文可能带 Key、内网地址或供应商名，只进日志不外发
        List<String> payloads = sentMessages().stream().map(JsonNode::toString).toList();
        assertThat(payloads).noneMatch(p -> p.contains("模型不可用"));
    }

    @Test
    void chat_blankContent_rejectedWithoutModelCall() throws Exception {
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"chat\",\"content\":\"   \"}"));

        assertThat(sentMessages().stream().map(m -> m.path("type").asText())).contains("error");
        verify(chatService, never()).chatStream(anyString());
    }

    @Test
    void chat_oversizedContent_rejectedBeforeQuotaCheck() throws Exception {
        // 配额按条数计量，单条长度上限是成本收口；超长消息不应再触达配额聚合与模型
        String oversized = "啊".repeat(ChatService.MAX_INPUT_CHARS + 1);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"chat\",\"content\":\"" + oversized + "\"}"));

        List<JsonNode> messages = sentMessages();
        assertThat(messages.stream().anyMatch(m -> "error".equals(m.path("type").asText())
                && m.path("data").asText().contains(String.valueOf(ChatService.MAX_INPUT_CHARS)))).isTrue();
        verify(quotaService, never()).checkSendMessage(anyString());
        verify(chatService, never()).chatStream(anyString());
    }

    @Test
    void selectedKbIds_areIntersectedWithVisibleDatasets() {
        when(knowledgeBaseService.retainVisibleDatasetIds(eq(List.of("d1", "d2")), eq("u1")))
                .thenReturn(List.of("d1"));

        handler.handleTextMessage(session,
                new TextMessage("{\"type\":\"selectedKbIds\",\"ids\":[\"d1\",\"d2\"]}"));

        verify(chatService).updateDataset(List.of("d1"));
    }

    @Test
    void close_writesPersonality_onlyForManager() throws Exception {
        when(chatService.close()).thenReturn(Map.of("personality", "新人设"));
        Assistant orgAssistant = new Assistant();
        orgAssistant.setId("a1");
        orgAssistant.setUserId("owner");
        orgAssistant.setOrgId("org-1");
        when(assistantService.getById("a1")).thenReturn(orgAssistant);
        when(orgService.hasRoleAtLeast("org-1", "u1", Org.ROLE_EDITOR)).thenReturn(true);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(assistantService).update(any(Assistant.class));
    }

    @Test
    void close_skipsPersonality_whenCallerLacksEditRole() throws Exception {
        when(chatService.close()).thenReturn(Map.of("personality", "新人设"));
        Assistant orgAssistant = new Assistant();
        orgAssistant.setId("a1");
        orgAssistant.setUserId("owner");
        orgAssistant.setOrgId("org-1");
        when(assistantService.getById("a1")).thenReturn(orgAssistant);
        when(orgService.hasRoleAtLeast("org-1", "u1", Org.ROLE_EDITOR)).thenReturn(false);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(assistantService, never()).update(any(Assistant.class));
    }

    @Test
    void close_persistsRecords_evenWithoutUri() throws Exception {
        // 落库不依赖权限分支：确保新增校验不会中断消息持久化
        when(chatService.close()).thenReturn(Map.of());
        when(assistantService.getById("a1")).thenReturn(null);
        when(chatService.drainPendingRecords()).thenReturn(List.of());

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(chatService).close();
    }

    // ===================== 落库时机与失败出口（v2.66 · C-133） =====================

    private static Record record(int role, String message) {
        Record r = new Record();
        r.setRole(role);
        r.setMessage(message);
        return r;
    }

    @Test
    void chat_success_persistsTurnBeforeQueryEnd() throws Exception {
        List<Record> pending = List.of(record(Record.ROLE_USER, "你好"), record(Record.ROLE_ASSISTANT, "你好呀"));
        when(chatService.chatStream("你好")).thenReturn(Flux.just(segmentChunk("你"), endChunk("你好呀")));
        when(chatService.drainPendingRecords()).thenReturn(pending);
        when(recordWriter.persist(pending, "a1", null, null))
                .thenReturn(new ConversationRecordWriter.Result(2, 0));

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"chat\",\"content\":\"你好\"}"));

        // 落库押在连接关闭上，等于让已生成的回答取决于连接怎么结束：正常收尾就必须落
        verify(recordWriter).persist(pending, "a1", null, null);
        List<String> types = sentMessages().stream().map(m -> m.path("type").asText()).toList();
        assertThat(types).doesNotContain("error");
        assertThat(types.get(types.size() - 1)).isEqualTo("query_end");
    }

    @Test
    void chat_persistFailure_emitsErrorFrameBeforeQueryEnd() throws Exception {
        List<Record> pending = List.of(record(Record.ROLE_ASSISTANT, "回答"));
        when(chatService.chatStream(anyString())).thenReturn(Flux.just(endChunk("回答")));
        when(chatService.drainPendingRecords()).thenReturn(pending);
        when(recordWriter.persist(pending, "a1", null, null))
                .thenReturn(new ConversationRecordWriter.Result(0, 1));

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"chat\",\"content\":\"你好\"}"));

        List<String> types = sentMessages().stream().map(m -> m.path("type").asText()).toList();
        // 落库失败只进日志时，用户以为已存进历史，刷新后才发现没了
        int errorIdx = types.indexOf("error");
        assertThat(errorIdx).as("落库失败必须下发 error 帧，实际: %s", types).isNotNegative();
        assertThat(types.indexOf("query_end")).as("error 帧要先于收尾帧: %s", types).isGreaterThan(errorIdx);
        String data = sentMessages().get(errorIdx).path("data").asText();
        assertThat(data).contains("1");
    }

    @Test
    void chat_partialPersistFailure_stillEmitsErrorFrame() throws Exception {
        // 与上一例成对：saved>0 但 failed>0 时同样要报——"存了一半"不是成功
        List<Record> pending = List.of(record(Record.ROLE_USER, "问"), record(Record.ROLE_ASSISTANT, "答"));
        when(chatService.chatStream(anyString())).thenReturn(Flux.just(endChunk("答")));
        when(chatService.drainPendingRecords()).thenReturn(pending);
        when(recordWriter.persist(pending, "a1", null, null))
                .thenReturn(new ConversationRecordWriter.Result(1, 1));

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"chat\",\"content\":\"你好\"}"));

        assertThat(sentMessages().stream().map(m -> m.path("type").asText())).contains("error");
    }

    @Test
    void close_fallback_persistsRemainingTurnOnlyOnce() throws Exception {
        // 兜底走同一个取出式队列：每轮已落库时这里取到空，不会重复插入
        List<Record> pending = List.of(record(Record.ROLE_ASSISTANT, "残量"));
        when(chatService.close()).thenReturn(Map.of());
        when(assistantService.getById("a1")).thenReturn(null);
        when(chatService.drainPendingRecords()).thenReturn(pending);
        when(recordWriter.persist(pending, "a1", null, null))
                .thenReturn(new ConversationRecordWriter.Result(1, 0));

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(recordWriter).persist(pending, "a1", null, null);
    }
}
