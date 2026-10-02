package com.leyon.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.entity.Record;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 聊天服务核心逻辑单元测试
 * 覆盖：空输入短路、流式输出推送、工具调用上限、挂断监听、会话记录导出、上下文截断、取消传播与中断轮次落库
 *
 * @author leyon
 */
class ChatServiceTest {

    private ModelAdapter modelAdapter;
    private KnowledgeProvider knowledgeProvider;
    private ObjectMapper objectMapper;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        modelAdapter = mock(ModelAdapter.class);
        knowledgeProvider = mock(KnowledgeProvider.class);
        objectMapper = new ObjectMapper();
        chatService = new ChatService(modelAdapter, knowledgeProvider, objectMapper,
                "你是测试助手", List.of(), List.of());
    }

    @Test
    void chatStream_blankInputReturnsEmpty() {
        StepVerifier.create(chatService.chatStream("   "))
                .expectComplete()
                .verify();
        StepVerifier.create(chatService.chatStream(""))
                .expectComplete()
                .verify();
    }

    @Test
    void chatStream_emitsSegmentsThenEnd() {
        // 单次流式响应：一个文本块
        Generation gen = new Generation(new AssistantMessage("你好"));
        ChatResponse response = new ChatResponse(List.of(gen));
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(Flux.just(response));

        StepVerifier.create(chatService.chatStream("你好"))
                .assertNext(chunk -> {
                    assertThat(chunk.get("segment")).isEqualTo("你好");
                    assertThat(chunk.get("streamEnd")).isEqualTo(false);
                })
                .assertNext(endChunk -> {
                    assertThat(endChunk.get("streamEnd")).isEqualTo(true);
                    assertThat(endChunk.get("message")).isEqualTo("你好");
                    assertThat(endChunk.get("role")).isEqualTo("assistant");
                })
                .expectComplete()
                .verify();
    }

    @Test
    void chatStream_savesConversationRecords() {
        Generation gen = new Generation(new AssistantMessage("回复内容"));
        ChatResponse response = new ChatResponse(List.of(gen));
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(Flux.just(response));

        StepVerifier.create(chatService.chatStream("用户问题")).expectNextCount(2).verifyComplete();

        List<Record> records = chatService.drainPendingRecords();
        assertThat(records).hasSize(2);
        assertThat(records.get(0).getRole()).isEqualTo(Record.ROLE_USER);
        assertThat(records.get(0).getMessage()).isEqualTo("用户问题");
        assertThat(records.get(1).getRole()).isEqualTo(Record.ROLE_ASSISTANT);
        assertThat(records.get(1).getMessage()).isEqualTo("回复内容");
        // 取出即清：逐轮落库与断开兜底共用同一个待落队列，第二次取必须是空，否则同一轮消息落两次
        assertThat(chatService.drainPendingRecords()).isEmpty();
    }

    @Test
    void hangupListener_firedOnHangupToolCall() {
        // 挂断监听在检测到 hangup 工具名时触发，不依赖工具回调是否注册
        ChatService voiceChat = new ChatService(modelAdapter, knowledgeProvider, objectMapper,
                "语音助手", List.of(), List.of());
        final String[] reason = new String[1];
        voiceChat.setHangupListener(r -> reason[0] = r);

        AssistantMessage assistantMsg = new AssistantMessage("", Map.of(),
                List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("tc1", "function", "hangup", "{}")));
        ChatResponse response = new ChatResponse(List.of(new Generation(assistantMsg)));
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(Flux.just(response));

        // 工具不存在时每轮推送 tool_call/tool_result 后递归，达到迭代上限后正常结束
        StepVerifier.create(voiceChat.chatStream("你好"))
                .thenConsumeWhile(x -> true)
                .verifyComplete();
        assertThat(reason[0]).isNotNull();
    }

    @Test
    void reset_clearsContextAndRecords() {
        chatService.changePrompt("新的人设");
        assertThat(chatService.drainPendingRecords()).isEmpty();
        // reset 后再生产记录也不受影响
        chatService.reset();
    }

    @Test
    void loadChatHistory_injectsMessagesWithoutDuplicatingRecords() {
        Record user = new Record();
        user.setRole(Record.ROLE_USER);
        user.setMessage("历史问题");
        Record assistant = new Record();
        assistant.setRole(Record.ROLE_ASSISTANT);
        assistant.setMessage("历史回答");

        chatService.loadChatHistory(List.of(user, assistant));
        // 历史只注入上下文，不写入待落库记录
        assertThat(chatService.drainPendingRecords()).isEmpty();

        // 新对话后历史仍参与上下文
        Generation gen = new Generation(new AssistantMessage("结合历史回答"));
        ChatResponse response = new ChatResponse(List.of(gen));
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(Flux.just(response));
        StepVerifier.create(chatService.chatStream("新问题")).expectNextCount(2).verifyComplete();
        // 落库记录仅包含本轮新消息（历史不重复落库）
        assertThat(chatService.drainPendingRecords()).hasSize(2);
    }

    /**
     * v2.68 · C-135：写入侧只产生 role 0/1，所以旧实现的 else 分支把"其它 role"折成
     * {@code ToolResponseMessage(id="", name="")} 注入上下文——那是一条对不上任何 tool_call 的悬空工具回执。
     */
    @Test
    void loadChatHistory_unknownRoleIsNotInjectedAsToolResponse() {
        Record toolCallRow = new Record();
        toolCallRow.setId("rec_role2");
        toolCallRow.setRole(Record.ROLE_TOOL_CALL);
        toolCallRow.setMessage("伪造的工具调用行");
        Record dirtyRow = new Record();
        dirtyRow.setId("rec_role9");
        dirtyRow.setRole(9);
        dirtyRow.setMessage("库里手工塞进来的脏行");
        Record userRow = new Record();
        userRow.setId("rec_role0");
        userRow.setRole(Record.ROLE_USER);
        userRow.setMessage("正常历史问题");

        chatService.loadChatHistory(List.of(toolCallRow, dirtyRow, userRow));

        Generation gen = new Generation(new AssistantMessage("结合历史回答"));
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(gen))));
        StepVerifier.create(chatService.chatStream("新问题")).thenConsumeWhile(x -> true).verifyComplete();

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(modelAdapter).stream(captor.capture());
        List<Message> instructions = captor.getValue().getInstructions();
        assertThat(instructions).noneMatch(m -> m instanceof ToolResponseMessage);
        assertThat(instructions).noneMatch(m -> "伪造的工具调用行".equals(m.getText())
                || "库里手工塞进来的脏行".equals(m.getText()));
        // 反向锚点：已知 role 照常注入，否则这条判据会被"全都不注入"的写法蒙过
        assertThat(instructions).anyMatch(m -> "正常历史问题".equals(m.getText()));
    }

    // ===================== 取消要传到在途的模型调用（v2.71 · C-139） =====================

    /**
     * 换消息或断开连接时的 dispose 此前只撤掉最外层：内层 {@code modelAdapter.stream(...).subscribe(...)}
     * 返回的 Disposable 被丢弃，于是上游照常吐块、照常计费，而这一轮的正文既不落库也无人消费
     * ——handler 里那句"停止流式订阅"形同注释。
     */
    @Test
    void cancel_propagatesToUpstreamModelSubscriptionAndSavesPartialTurn() {
        AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(Flux.just(
                        new ChatResponse(List.of(new Generation(new AssistantMessage("前半")))),
                        new ChatResponse(List.of(new Generation(new AssistantMessage("后半")))))
                .concatWith(Flux.never())
                .doOnCancel(() -> upstreamCancelled.set(true)));

        Disposable subscription = chatService.chatStream("用户问题").subscribe(chunk -> {
        });
        subscription.dispose();

        assertThat(upstreamCancelled).isTrue();
        // 中断的轮次同样落库：正文＝已经生成并推出去的那一段
        List<Record> pending = chatService.drainPendingRecords();
        assertThat(pending).hasSize(2);
        assertThat(pending.get(0).getMessage()).isEqualTo("用户问题");
        assertThat(pending.get(1).getMessage()).isEqualTo("前半后半");
        // 取出即清：取消路径也只能计入一次，否则同一轮回答落两遍
        assertThat(chatService.drainPendingRecords()).isEmpty();
    }

    /**
     * 反向锚点：正常收尾（onComplete 已落库）之后的 dispose 不得再触发一次落库，
     * 否则"取消即落库"会变成每轮双份记录。
     */
    @Test
    void disposeAfterNormalCompletionDoesNotSaveTheTurnAgain() {
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("完整回答"))))));

        List<Map<String, Object>> seen = new ArrayList<>();
        Disposable subscription = chatService.chatStream("用户问题").subscribe(seen::add);
        subscription.dispose();

        // 流在 subscribe 内即已正常收尾：分段 + 结束帧，且没有第二份记录
        assertThat(seen).hasSize(2);
        assertThat(chatService.drainPendingRecords()).hasSize(2);
        assertThat(chatService.drainPendingRecords()).isEmpty();
    }

    // ===================== 知识库检索失败必须与"无命中"可辨（v2.39） =====================

    @Test
    void chatStream_retrievalFailureIsMarkedFailedInEndChunk() {
        // 构造器参数顺序是 (personality, knowledgeIds, toolCallbacks)，知识库在前
        ChatService kbChat = new ChatService(modelAdapter, knowledgeProvider, objectMapper,
                "你是测试助手", List.of("kb-1"), List.of());
        when(knowledgeProvider.queryKnowledgeBaseWithDetail(any(), any()))
                .thenThrow(new RuntimeException("RAGFlow 连接超时"));
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("无参考也照常回答"))))));

        // 检索失败不阻断主流程，但收尾分片必须把"失败"与"没查到"区分开
        StepVerifier.create(kbChat.chatStream("问题"))
                .expectNextCount(1)
                .assertNext(endChunk -> {
                    Map<String, Object> kb = knowledgebaseOf(endChunk);
                    assertThat(kb.get("failed")).isEqualTo(true);
                    assertThat(kb.get("docCount")).isEqualTo(0);
                })
                .verifyComplete();
    }

    @Test
    void chatStream_retrievalWithoutHitsIsNotMarkedFailed() {
        ChatService kbChat = new ChatService(modelAdapter, knowledgeProvider, objectMapper,
                "你是测试助手", List.of("kb-1"), List.of());
        when(knowledgeProvider.queryKnowledgeBaseWithDetail(any(), any()))
                .thenReturn(new KnowledgeProvider.KnowledgeHit("", 0, List.of()));
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("知识库确实没有相关内容"))))));

        // 与上一例成对：docCount 同为 0，只有"失败"这一维不同；若两例同结果则失败标记形同虚设
        StepVerifier.create(kbChat.chatStream("问题"))
                .expectNextCount(1)
                .assertNext(endChunk -> assertThat(knowledgebaseOf(endChunk).get("failed")).isEqualTo(false))
                .verifyComplete();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> knowledgebaseOf(Map<String, Object> endChunk) {
        return (Map<String, Object>) endChunk.get("knowledgebase");
    }

    // ===================== 检索状态必须落库，历史回看才可辨（v2.41 · C-90） =====================

    /** 取出本轮待落库记录（落库顺序恒为 [user, assistant]） */
    private static Record assistantRecordOf(ChatService service) {
        List<Record> records = service.drainPendingRecords();
        assertThat(records).hasSize(2);
        return records.get(1);
    }

    @Test
    void saveConversation_persistsKnowledgebaseHitShape() throws Exception {
        ChatService kbChat = new ChatService(modelAdapter, knowledgeProvider, objectMapper,
                "你是测试助手", List.of("kb-1"), List.of());
        when(knowledgeProvider.queryKnowledgeBaseWithDetail(any(), any()))
                .thenReturn(new KnowledgeProvider.KnowledgeHit("参考内容", 2, List.of("A.pdf", "B.pdf")));
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("带参考的回答"))))));

        StepVerifier.create(kbChat.chatStream("问题")).expectNextCount(2).verifyComplete();

        // 落库形状与 query_end 帧同名同形，历史回看可直接复用前端 knowledgebaseFlag()
        Map<String, Object> stored = objectMapper.readValue(
                assistantRecordOf(kbChat).getKnowledgebaseInfo(), Map.class);
        assertThat(stored).containsEntry("docCount", 2)
                .containsEntry("docName", List.of("A.pdf", "B.pdf"))
                .containsEntry("failed", false);
    }

    @Test
    void saveConversation_persistsNoHitShape() throws Exception {
        ChatService kbChat = new ChatService(modelAdapter, knowledgeProvider, objectMapper,
                "你是测试助手", List.of("kb-1"), List.of());
        when(knowledgeProvider.queryKnowledgeBaseWithDetail(any(), any()))
                .thenReturn(new KnowledgeProvider.KnowledgeHit("", 0, List.of()));
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("知识库确实没有相关内容"))))));

        StepVerifier.create(kbChat.chatStream("问题")).expectNextCount(2).verifyComplete();

        // 与失败例成对：docCount 同为 0，只有 failed 这一维不同；两例同落库形状则落库不可辨
        Map<String, Object> stored = objectMapper.readValue(
                assistantRecordOf(kbChat).getKnowledgebaseInfo(), Map.class);
        assertThat(stored).containsEntry("docCount", 0)
                .containsEntry("docName", List.of())
                .containsEntry("failed", false);
    }

    @Test
    void saveConversation_persistsRetrievalFailureShape() throws Exception {
        ChatService kbChat = new ChatService(modelAdapter, knowledgeProvider, objectMapper,
                "你是测试助手", List.of("kb-1"), List.of());
        when(knowledgeProvider.queryKnowledgeBaseWithDetail(any(), any()))
                .thenThrow(new RuntimeException("RAGFlow 连接超时"));
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("无参考也照常回答"))))));

        StepVerifier.create(kbChat.chatStream("问题")).expectNextCount(2).verifyComplete();

        Map<String, Object> stored = objectMapper.readValue(
                assistantRecordOf(kbChat).getKnowledgebaseInfo(), Map.class);
        assertThat(stored).containsEntry("docCount", 0)
                .containsEntry("docName", List.of())
                .containsEntry("failed", true);
    }

    @Test
    void saveConversation_leavesKnowledgebaseInfoNullWhenNoKnowledgeBaseConfigured() {
        Generation gen = new Generation(new AssistantMessage("回复内容"));
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(gen))));

        StepVerifier.create(chatService.chatStream("用户问题")).expectNextCount(2).verifyComplete();

        // 未挂知识库与会话本身无关，不该凭空写一个"0 篇引用"的假状态；取出一次即覆盖 user/assistant 两条
        List<Record> pending = chatService.drainPendingRecords();
        assertThat(pending).hasSize(2);
        assertThat(pending.get(1).getKnowledgebaseInfo()).isNull();
        // 用户消息不携带检索状态
        assertThat(pending.get(0).getKnowledgebaseInfo()).isNull();
    }

    @Test
    void saveConversation_knowledgebaseInfoIsValidJsonForMysqlColumn() throws Exception {
        // MySQL JSON 列对非法文本直接报 3140，落库文本必须是可解析 JSON
        ChatService kbChat = new ChatService(modelAdapter, knowledgeProvider, objectMapper,
                "你是测试助手", List.of("kb-1"), List.of());
        when(knowledgeProvider.queryKnowledgeBaseWithDetail(any(), any()))
                .thenReturn(new KnowledgeProvider.KnowledgeHit("参考内容", 1, List.of("含\"引号\".pdf")));
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("回答"))))));

        StepVerifier.create(kbChat.chatStream("问题")).expectNextCount(2).verifyComplete();

        String raw = assistantRecordOf(kbChat).getKnowledgebaseInfo();
        assertThat(raw).doesNotStartWith("null").isNotBlank();
        assertThat(objectMapper.readTree(raw).path("docName").get(0).asText()).isEqualTo("含\"引号\".pdf");
    }

    // ===================== 工具回路在应用侧真实流动（v2.72 · C-141，S-24/S-19 收口） =====================

    /** 造一个名叫 weather、返回固定结果的可执行工具回调 */
    private ToolCallback weatherToolReturning(String rawResult) {
        ToolCallback weather = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("weather");
        when(weather.getToolDefinition()).thenReturn(definition);
        when(weather.call(anyString())).thenReturn(rawResult);
        return weather;
    }

    private static AssistantMessage weatherToolCallMessage(String roundText, String arguments) {
        return new AssistantMessage(roundText, Map.of(),
                List.of(new AssistantMessage.ToolCall("tc1", "function", "weather", arguments)));
    }

    // ===================== 失败回合也要留痕（v2.73 · C-143，S-22 收口） =====================

    @Test
    void modelError_persistsFailedTurnWithCategoryReason() {
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("upstream 500")));

        StepVerifier.create(chatService.chatStream("这条会失败的问题"))
                .expectError(RuntimeException.class)
                .verify();

        // 用户的话不再消失：失败回合落 [user, assistant(空正文 + fail_reason)]；此前该会话 records 0 行
        List<Record> rows = chatService.drainPendingRecords();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getRole()).isEqualTo(Record.ROLE_USER);
        assertThat(rows.get(0).getMessage()).isEqualTo("这条会失败的问题");
        assertThat(rows.get(1).getRole()).isEqualTo(Record.ROLE_ASSISTANT);
        assertThat(rows.get(1).getFailReason()).isEqualTo(ChatService.TURN_FAIL_REASON);
        // 取出即清：错误路径也只能计入一次
        assertThat(chatService.drainPendingRecords()).isEmpty();
    }

    @Test
    void modelErrorAfterPartialText_persistsPartialAndMarksFailed() {
        Generation gen = new Generation(new AssistantMessage("已经吐出的前半"));
        when(modelAdapter.stream(any(Prompt.class)))
                .thenReturn(Flux.just(new ChatResponse(List.of(gen))).concatWith(Flux.error(new RuntimeException("boom"))));

        StepVerifier.create(chatService.chatStream("问"))
                .expectNextCount(1)
                .expectError(RuntimeException.class)
                .verify();

        List<Record> rows = chatService.drainPendingRecords();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).getMessage()).isEqualTo("已经吐出的前半");
        assertThat(rows.get(1).getFailReason()).isEqualTo(ChatService.TURN_FAIL_REASON);
    }

    @Test
    void successTurn_carriesNoFailReason() {
        Generation gen = new Generation(new AssistantMessage("正常回答"));
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(gen))));
        StepVerifier.create(chatService.chatStream("问")).expectNextCount(2).verifyComplete();
        List<Record> rows = chatService.drainPendingRecords();
        assertThat(rows.get(1).getFailReason()).isNull();
    }

    @Test
    void buildPrompt_disablesInternalToolExecutionOnlyWhenToolsPresent() {
        // S-24 的判据本体：撤掉 buildPrompt 里那行开关，其余全部单测照样绿（ModelAdapter 是 mock，
        // 看不见 Spring AI 框架内执行）——所以取值本身必须在这里钉住
        ChatService toolChat = new ChatService(modelAdapter, knowledgeProvider, objectMapper,
                "工具助手", List.of(), List.of(weatherToolReturning("{}")));
        ChatService plainChat = new ChatService(modelAdapter, knowledgeProvider, objectMapper,
                "普通助手", List.of(), List.of());
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(Flux.empty());

        toolChat.chatStream("问").collectList().block(Duration.ofSeconds(5));
        plainChat.chatStream("问").collectList().block(Duration.ofSeconds(5));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(modelAdapter, times(2)).stream(captor.capture());
        OpenAiChatOptions withTools = (OpenAiChatOptions) captor.getAllValues().get(0).getOptions();
        assertThat(withTools.getInternalToolExecutionEnabled()).isFalse();
        OpenAiChatOptions noTools = (OpenAiChatOptions) captor.getAllValues().get(1).getOptions();
        assertThat(noTools.getInternalToolExecutionEnabled()).as("无工具助手不加开关（零行为变更）").isNull();
    }

    @Test
    void toolRound_framesFlowAndTrajectoryPersists() {        ChatService toolChat = new ChatService(modelAdapter, knowledgeProvider, objectMapper,
                "工具助手", List.of(), List.of(weatherToolReturning("{\"content\":\"晴，25 度\"}")));
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(
                Flux.just(new ChatResponse(List.of(new Generation(weatherToolCallMessage("让我查一下", "{\"city\":\"北京\"}"))))),
                Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("今天晴"))))));

        List<Map<String, Object>> frames = toolChat.chatStream("北京天气怎么样")
                .collectList().block(Duration.ofSeconds(5));

        // 帧序：正文分段（工具前的气泡）→ tool_call → tool_result → 续答分段 → 收尾；此前框架内执行吞掉工具帧
        assertThat(frames).hasSize(5);
        assertThat(frames.get(0)).containsEntry("segment", "让我查一下");
        assertThat(frames.get(1)).containsEntry("type", "tool_call").containsEntry("toolName", "weather");
        assertThat(frames.get(2)).containsEntry("type", "tool_result");
        @SuppressWarnings("unchecked")
        Map<String, Object> resultData = (Map<String, Object>) frames.get(2).get("data");
        assertThat(resultData).containsEntry("name", "weather").containsEntry("success", true);
        assertThat((String) resultData.get("result")).contains("晴，25 度");
        assertThat(frames.get(3)).containsEntry("segment", "今天晴");
        assertThat(frames.get(4)).containsEntry("streamEnd", true);

        // 轨迹落库顺序：user → 工具前正文 → tool_call → tool_result → 最终回答
        List<Record> rows = toolChat.drainPendingRecords();
        assertThat(rows).hasSize(5);
        assertThat(rows.get(0).getRole()).isEqualTo(Record.ROLE_USER);
        assertThat(rows.get(1).getRole()).isEqualTo(Record.ROLE_ASSISTANT);
        assertThat(rows.get(1).getMessage()).isEqualTo("让我查一下");
        assertThat(rows.get(2).getRole()).isEqualTo(Record.ROLE_TOOL_CALL);
        assertThat(rows.get(2).getToolName()).isEqualTo("weather");
        assertThat(rows.get(2).getToolArgs()).isEqualTo("{\"city\":\"北京\"}");
        assertThat(rows.get(3).getRole()).isEqualTo(Record.ROLE_TOOL_RESULT);
        assertThat(rows.get(3).getToolResult()).isEqualTo("\"晴，25 度\"");
        assertThat(rows.get(4).getRole()).isEqualTo(Record.ROLE_ASSISTANT);
        assertThat(rows.get(4).getMessage()).isEqualTo("今天晴");
        // 取出即清
        assertThat(toolChat.drainPendingRecords()).isEmpty();
    }

    @Test
    void toolRound_followUpAndNextTurnCarryValidToolExchange() {
        ChatService toolChat = new ChatService(modelAdapter, knowledgeProvider, objectMapper,
                "工具助手", List.of(), List.of(weatherToolReturning("{\"content\":\"晴\"}")));
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(
                Flux.just(new ChatResponse(List.of(new Generation(weatherToolCallMessage("", "{}"))))),
                Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("今天晴"))))),
                Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("明天更暖"))))));

        toolChat.chatStream("北京天气怎么样").collectList().block(Duration.ofSeconds(5));
        toolChat.chatStream("那明天呢").collectList().block(Duration.ofSeconds(5));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(modelAdapter, times(3)).stream(captor.capture());
        // 续答请求：用户原话在上下文里、assistant(tool_calls) 紧跟工具回执
        List<Message> followUp = captor.getAllValues().get(1).getInstructions();
        assertThat(followUp).anyMatch(m -> m instanceof UserMessage u && "北京天气怎么样".equals(u.getText()));
        assertThat(followUp).anyMatch(m -> m instanceof ToolResponseMessage);
        // 下一轮请求：上一轮的工具交换完整保留，且序列合法
        List<Message> nextTurn = captor.getAllValues().get(2).getInstructions();
        assertThat(nextTurn).anyMatch(m -> m instanceof ToolResponseMessage);
        assertThat(nextTurn.get(0) instanceof ToolResponseMessage).as("请求不得以悬空工具回执开头").isFalse();
        assertNoDanglingToolCalls(nextTurn);
    }

    @Test
    void toolRound_malformedArgsAndPlainTextResultStayJsonSafe() throws Exception {
        ChatService toolChat = new ChatService(modelAdapter, knowledgeProvider, objectMapper,
                "工具助手", List.of(), List.of(weatherToolReturning("plain text result")));
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(
                Flux.just(new ChatResponse(List.of(new Generation(weatherToolCallMessage("", "{\"city\":\"北京\""))))),
                Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("好的"))))));

        toolChat.chatStream("北京天气怎么样").collectList().block(Duration.ofSeconds(5));

        // tool_args/tool_result 是 JSON 列：坏参与非 JSON 结果折成 JSON 字符串标量，否则 MySQL 3140 使整批落库失败；
        // message 列 NOT NULL 无默认值，轨迹行落空串（真机取证抓到的第一处）
        List<Record> rows = toolChat.drainPendingRecords();
        assertThat(rows.get(1).getMessage()).isEmpty();
        assertThat(rows.get(2).getMessage()).isEmpty();
        assertThat(rows.get(1).getToolArgs()).isNotBlank();
        assertThat(objectMapper.readTree(rows.get(1).getToolArgs()).isTextual()).isTrue();
        assertThat(objectMapper.readTree(rows.get(2).getToolResult()).asText()).isEqualTo("plain text result");
    }

    @Test
    void toolRound_jsonStringScalarToolResultIsUnwrapped() throws Exception {
        // 字符串型工具经 FunctionToolCallback 序列化成 JSON 字符串标量：展示给用户的是原文，不是带引号的序列化形状
        ChatService toolChat = new ChatService(modelAdapter, knowledgeProvider, objectMapper,
                "工具助手", List.of(), List.of(weatherToolReturning("\"直接文本结果\"")));
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(
                Flux.just(new ChatResponse(List.of(new Generation(weatherToolCallMessage("", "{}"))))),
                Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("好的"))))));

        List<Map<String, Object>> frames = toolChat.chatStream("北京天气怎么样")
                .collectList().block(Duration.ofSeconds(5));
        @SuppressWarnings("unchecked")
        Map<String, Object> resultData = (Map<String, Object>) frames.get(1).get("data");
        assertThat(resultData.get("result")).isEqualTo("直接文本结果");

        List<Record> rows = toolChat.drainPendingRecords();
        assertThat(objectMapper.readTree(rows.get(2).getToolResult()).asText()).isEqualTo("直接文本结果");
    }

    @Test
    void dropHeadToolGroupFragments_neverLeavesHalfGroup() {
        AssistantMessage toolCallHead = new AssistantMessage("", Map.of(),
                List.of(new AssistantMessage.ToolCall("tc1", "function", "weather", "{}")));
        ToolResponseMessage toolReply = new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("tc1", "weather", "晴")));
        // 均匀的回合节奏里截断永远落在组边界上，切开只发生在不均匀的历史里——所以直接构造切开的两半
        List<Message> orphanReply = new ArrayList<>(List.of(toolReply, new UserMessage("接续问题")));
        ChatService.dropHeadToolGroupFragments(orphanReply);
        assertThat(orphanReply).hasSize(1);
        assertThat(orphanReply.get(0)).isInstanceOf(UserMessage.class);

        List<Message> danglingToolCalls = new ArrayList<>(List.of(toolCallHead, toolReply, new UserMessage("接续问题")));
        ChatService.dropHeadToolGroupFragments(danglingToolCalls);
        assertThat(danglingToolCalls).hasSize(1);
        assertThat(danglingToolCalls.get(0)).isInstanceOf(UserMessage.class);

        // 反向锚点：普通助手消息打头（归档截走用户行后的合法形状）不许被顺手吃掉
        List<Message> healthy = new ArrayList<>(List.of(new AssistantMessage("历史回答"), new UserMessage("问题")));
        ChatService.dropHeadToolGroupFragments(healthy);
        assertThat(healthy).hasSize(2);
    }

    @Test
    void trimHistory_afterManyToolTurnsRequestNeverStartsInsideToolGroup() {
        ChatService toolChat = new ChatService(modelAdapter, knowledgeProvider, objectMapper,
                "工具助手", List.of(), List.of(weatherToolReturning("{\"content\":\"晴\"}")));
        ChatResponse toolRound = new ChatResponse(List.of(
                new Generation(weatherToolCallMessage("", "{}"))));
        ChatResponse textRound = new ChatResponse(List.of(new Generation(new AssistantMessage("答"))));
        AtomicInteger call = new AtomicInteger();
        when(modelAdapter.stream(any(Prompt.class))).thenAnswer(inv ->
                call.getAndIncrement() % 2 == 0 ? Flux.just(toolRound) : Flux.just(textRound));

        // 每个工具回合给上下文净增 4 条（user / assistant(tool_calls) / tool / assistant），
        // 逐轮把最早的消息挤出 60 条窗口；最早的工具组最终会被推到窗口头部，请求序列必须仍然合法
        for (int i = 0; i < 18; i++) {
            toolChat.chatStream("问" + i).collectList().block(Duration.ofSeconds(5));
        }

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(modelAdapter, atLeast(2)).stream(captor.capture());
        List<Message> last = captor.getAllValues().get(captor.getAllValues().size() - 1).getInstructions();
        assertThat(last).isNotEmpty();
        assertThat(last.get(0) instanceof ToolResponseMessage).as("请求不得以悬空工具回执开头").isFalse();
        assertNoDanglingToolCalls(last);
    }

    /** 请求序列合法性的判据：每个带 tool_calls 的助手消息必须紧跟工具回执 */
    private static void assertNoDanglingToolCalls(List<Message> instructions) {
        for (int i = 0; i < instructions.size(); i++) {
            Message m = instructions.get(i);
            if (m instanceof AssistantMessage a && !a.getToolCalls().isEmpty()) {
                boolean followedByToolResult = i + 1 < instructions.size()
                        && instructions.get(i + 1) instanceof ToolResponseMessage;
                assertThat(followedByToolResult).as("tool_calls 必须紧跟工具回执，位置 %s", i).isTrue();
            }
        }
    }
}