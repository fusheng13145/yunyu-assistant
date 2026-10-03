package com.leyon.backend.service;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * OpenAI 兼容模型适配器（DeepSeek、OpenAI 等）
 * 基于 Spring AI ChatModel 实现流式对话能力
 *
 * @author leyon
 */
@Component
public class OpenAiModelAdapter implements ModelAdapter {

    private final ChatModel chatModel;

    /**
     * 流式静默超时（毫秒）：全仓唯一的模型流超时判据。
     * 三条对话通道（文本 WS / 语音 WS / 开放 SSE）与每一轮工具续答都只经这一个出口，
     * 所以超时挂在这里就等价于"任何一次对话回合都不会无限挂住"——管的是回合，不是交换：
     * 到点只结束本轮，到模型服务的在途 HTTP 交换并不因此中止（手册 6.6 的 v2.71 边界 / 7.4 的 S-26）。
     */
    private final Duration streamTimeout;

    public OpenAiModelAdapter(ChatModel chatModel,
                              @Value("${app.ai.stream-timeout-ms}") long streamTimeoutMs) {
        this.chatModel = chatModel;
        this.streamTimeout = Duration.ofMillis(streamTimeoutMs);
    }

    /**
     * 发起流式对话，并施加**静默**超时（每收到一个分块重新计时，不是整轮总时长上限）：
     * 慢而持续推进的回答不受影响，上游不再吐分块时在超时到点以 TimeoutException 收场。
     * 此前这里没有任何超时：模型侧 TCP 半开或网关静默丢流时，本轮既不成功也不失败，
     * 前端的打字态、WS 订阅与会话状态会一直悬着，且没有任何日志能说清是谁没答。
     * <p>
     * 分帧节奏另有一层：Spring AI 1.0.0 的 stream() 用 {@code buffer(2, 1)} 为累计用量攒相邻两块，
     * 第 N 块要等第 N+1 块到达才向下游发出，最后一块只在流正常收尾时补发。真机读数（手册 7.5 v2.71 行）：
     * 上游两拍间隔 150ms，前端两帧间隔 2ms；中断轮次落库的正文比上游已吐出的少最后一块。
     * <p>
     * 两个收口（v2.77 · C-148 / C-149）：
     * <ul>
     *   <li><b>取消传播到交换层</b>——WebClient 连接器按类路径择优，引入 reactor-netty 后
     *       {@code dispose} 会拆断到模型服务的 TCP 交换（此前 JDK HttpClient 不随订阅取消而拆，
     *       用户已走后上游照跑完一轮，S-26）；</li>
     *   <li><b>回调线程与共享 worker 解耦</b>——S-25：模型流的 onNext/onComplete/落库此前跑在
     *       JDK HttpClient 的共享 worker 上，一条连接的慢库写会拖垮其他在途调用；
     *       {@code publishOn(boundedElastic)} 把整条下游链路（含取消回调，CAS 守卫为其而立）搬到
     *       有界弹性池。</li>
     * </ul>
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return chatModel.stream(prompt)
                .timeout(streamTimeout)
                .publishOn(Schedulers.boundedElastic());
    }
}
