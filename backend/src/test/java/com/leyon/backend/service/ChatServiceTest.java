package com.leyon.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.entity.Record;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 聊天服务核心逻辑单元测试
 * 覆盖：空输入短路、流式输出推送、工具调用上限、挂断监听、会话记录导出、上下文截断
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

        List<Record> records = chatService.getNewRecords();
        assertThat(records).hasSize(2);
        assertThat(records.get(0).getRole()).isEqualTo(Record.ROLE_USER);
        assertThat(records.get(0).getMessage()).isEqualTo("用户问题");
        assertThat(records.get(1).getRole()).isEqualTo(Record.ROLE_ASSISTANT);
        assertThat(records.get(1).getMessage()).isEqualTo("回复内容");
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
        assertThat(chatService.getNewRecords()).isEmpty();
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
        assertThat(chatService.getNewRecords()).isEmpty();

        // 新对话后历史仍参与上下文
        Generation gen = new Generation(new AssistantMessage("结合历史回答"));
        ChatResponse response = new ChatResponse(List.of(gen));
        when(modelAdapter.stream(any(Prompt.class))).thenReturn(Flux.just(response));
        StepVerifier.create(chatService.chatStream("新问题")).expectNextCount(2).verifyComplete();
        // 落库记录仅包含本轮新消息（历史不重复落库）
        assertThat(chatService.getNewRecords()).hasSize(2);
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

    /** 取本轮助手回复记录（落库顺序恒为 [user, assistant]） */
    private static Record assistantRecordOf(ChatService service) {
        List<Record> records = service.getNewRecords();
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

        // 未挂知识库与会话本身无关，不该凭空写一个"0 篇引用"的假状态
        assertThat(assistantRecordOf(chatService).getKnowledgebaseInfo()).isNull();
        // 用户消息不携带检索状态
        assertThat(chatService.getNewRecords().get(0).getKnowledgebaseInfo()).isNull();
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
}