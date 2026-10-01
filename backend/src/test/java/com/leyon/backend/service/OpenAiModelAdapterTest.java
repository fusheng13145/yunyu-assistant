package com.leyon.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模型流式适配器的超时判据（v2.71）
 * 覆盖：静默超时按配置窗口触发、超时撤掉在途调用、慢而持续推进的流不被掐断
 *
 * @author leyon
 */
class OpenAiModelAdapterTest {

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /**
     * 上游一个字都不吐时，本轮必须在配置窗口内以 TimeoutException 收场，而不是无限挂住。
     * 用虚拟时间把窗口两侧都锁住：提前触发（单位写错）会撞在 expectNextCount 之前，
     * 迟迟不触发（写成 ofSeconds）会让 verify 等不到错误信号。
     */
    @Test
    void stream_silenceTimeoutAbortsHungUpstreamAndCancelsIt() {
        ChatModel chatModel = mock(ChatModel.class);
        AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
        OpenAiModelAdapter adapter = new OpenAiModelAdapter(chatModel, 1000);
        when(chatModel.stream(any(Prompt.class)))
                .thenAnswer(invocation -> Flux.<ChatResponse>never().doOnCancel(() -> upstreamCancelled.set(true)));

        StepVerifier.withVirtualTime(() -> adapter.stream(new Prompt("你好")))
                .thenAwait(Duration.ofMillis(900))
                .expectNextCount(0)
                .thenAwait(Duration.ofMillis(200))
                .expectError(TimeoutException.class)
                .verify();

        // 超时不只是"下面收到一个错误"：上游订阅必须真的被撤掉，否则在途调用继续吐块、继续计费
        assertThat(upstreamCancelled).isTrue();
    }

    /**
     * 反向锚点：静默超时约束的是"卡住"，不是"回答长"。
     * 每 100ms 吐一块、共 5 块（总时长 500ms）在 250ms 窗口下必须完整跑完——
     * 写成总时长上限（take/timeout(Duration) 之外的自创计时）的实现会在第 3 块前就报错。
     */
    @Test
    void stream_progressingStreamCompletesWithinSameWindow() {
        ChatModel chatModel = mock(ChatModel.class);
        OpenAiModelAdapter adapter = new OpenAiModelAdapter(chatModel, 250);
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> Flux.interval(Duration.ofMillis(100))
                .take(5)
                .map(i -> chunk("块" + i)));

        StepVerifier.withVirtualTime(() -> adapter.stream(new Prompt("你好")))
                .thenAwait(Duration.ofSeconds(1))
                .expectNextCount(5)
                .verifyComplete();
    }
}
