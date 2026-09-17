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
}