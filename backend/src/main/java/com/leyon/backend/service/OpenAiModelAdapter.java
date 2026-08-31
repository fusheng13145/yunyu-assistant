package com.leyon.backend.service;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * OpenAI 兼容模型适配器（DeepSeek、OpenAI 等）
 * 基于 Spring AI ChatModel 实现流式对话能力
 *
 * @author leyon
 */
@Component
public class OpenAiModelAdapter implements ModelAdapter {

    private final ChatModel chatModel;
    private final String modelName;

    public OpenAiModelAdapter(ChatModel chatModel,
                               @Value("${spring.ai.chat.options.model:deepseek-chat}") String modelName) {
        this.chatModel = chatModel;
        this.modelName = modelName;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return chatModel.stream(prompt);
    }

    @Override
    public String getModelName() {
        return modelName;
    }
}
