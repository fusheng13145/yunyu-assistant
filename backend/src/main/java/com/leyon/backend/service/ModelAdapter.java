package com.leyon.backend.service;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * AI 模型适配器接口
 * 支持多种 AI 模型的统一接入，解耦 ChatService 与具体模型实现
 *
 * @author leyon
 */
public interface ModelAdapter {

    /**
     * 流式对话
     *
     * @param prompt 对话提示词
     * @return 流式响应数据
     */
    Flux<ChatResponse> stream(Prompt prompt);

    /**
     * 获取当前适配器使用的模型名称
     */
    String getModelName();
}
