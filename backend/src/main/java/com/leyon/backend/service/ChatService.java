package com.leyon.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.entity.ChatMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

public class ChatService {

    private final ChatModel chatModel;
    private final KnowledgeService knowledgeService;
    private final ObjectMapper objectMapper;
    private String systemPrompt;
    private List<String> knowledgeIds;
    private final List<Message> conversationHistory;
    private final List<ChatMessage> chatMessages;
    private final List<String> toolNames;

    public ChatService(ChatModel chatModel, KnowledgeService knowledgeService,
                       ObjectMapper objectMapper, String personality, List<String> knowledgeIds,
                       List<String> toolNames) {
        this.chatModel = chatModel;
        this.knowledgeService = knowledgeService;
        this.objectMapper = objectMapper;
        this.systemPrompt = personality != null ? personality : "";
        this.knowledgeIds = knowledgeIds != null ? knowledgeIds : new ArrayList<>();
        this.conversationHistory = new ArrayList<>();
        this.chatMessages = new ArrayList<>();
        this.toolNames = toolNames != null ? toolNames : new ArrayList<>();
    }

    public Flux<Map<String, Object>> chatStream(String text) {
        long startTime = System.currentTimeMillis();

        String effectiveSystemPrompt = systemPrompt;

        if (knowledgeIds != null && !knowledgeIds.isEmpty()) {
            try {
                String knowledgeContext = knowledgeService.queryKnowledgeBase(text, knowledgeIds);
                if (!knowledgeContext.isEmpty()) {
                    effectiveSystemPrompt += "\n\n以下是从知识库中检索到的相关信息：\n" + knowledgeContext;
                }
            } catch (Exception ignored) {
            }
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(effectiveSystemPrompt));
        messages.addAll(conversationHistory);
        messages.add(new UserMessage(text));

        Prompt prompt;
        if (!toolNames.isEmpty()) {
            OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder();
            optionsBuilder.toolNames(toolNames.toArray(new String[0]));
            prompt = new Prompt(messages, optionsBuilder.build());
        } else {
            prompt = new Prompt(messages);
        }

        StringBuilder fullResponse = new StringBuilder();

        Flux<Map<String, Object>> contentFlux = chatModel.stream(prompt)
                .map(chatResponse -> {
                    if (chatResponse.getResult() != null
                            && chatResponse.getResult().getOutput() != null) {
                        String content = chatResponse.getResult().getOutput().getText();
                        return content != null ? content : "";
                    }
                    return "";
                })
                .filter(content -> !content.isEmpty())
                .doOnNext(fullResponse::append)
                .map(content -> {
                    Map<String, Object> chunk = new HashMap<>();
                    chunk.put("segment", content);
                    chunk.put("streamEnd", false);
                    return chunk;
                });

        Flux<Map<String, Object>> endFlux = Flux.defer(() -> {
            long costTime = System.currentTimeMillis() - startTime;
            conversationHistory.add(new UserMessage(text));
            conversationHistory.add(new AssistantMessage(fullResponse.toString()));

            ChatMessage userMsg = new ChatMessage();
            userMsg.setRole("user");
            userMsg.setMessage(text);
            chatMessages.add(userMsg);

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setRole("assistant");
            assistantMsg.setMessage(fullResponse.toString());
            assistantMsg.setCostTime(costTime);
            chatMessages.add(assistantMsg);

            Map<String, Object> endChunk = new HashMap<>();
            endChunk.put("segment", "");
            endChunk.put("streamEnd", true);
            endChunk.put("message", fullResponse.toString());
            endChunk.put("costTime", costTime);
            endChunk.put("role", "assistant");
            return Flux.just(endChunk);
        });

        return Flux.concat(contentFlux, endFlux);
    }

    public void loadChatHistory(List<ChatMessage> history) {
        if (history == null) {
            return;
        }
        this.chatMessages.addAll(history);
        for (ChatMessage msg : history) {
            if ("user".equals(msg.getRole())) {
                conversationHistory.add(new UserMessage(msg.getMessage()));
            } else if ("assistant".equals(msg.getRole())) {
                conversationHistory.add(new AssistantMessage(msg.getMessage()));
            }
        }
    }

    public void changePrompt(String prompt) {
        this.systemPrompt = prompt != null ? prompt : "";
        this.conversationHistory.clear();
        this.chatMessages.clear();
    }

    public void updateDataset(List<String> ids) {
        this.knowledgeIds = ids != null ? ids : new ArrayList<>();
    }

    public void reset() {
        this.conversationHistory.clear();
        this.chatMessages.clear();
    }

    public Map<String, String> close() {
        Map<String, String> state = new HashMap<>();
        try {
            state.put("chatMessage", objectMapper.writeValueAsString(chatMessages));
        } catch (Exception e) {
            state.put("chatMessage", "[]");
        }
        state.put("personality", systemPrompt);
        try {
            state.put("knowledgeIds", objectMapper.writeValueAsString(knowledgeIds));
        } catch (Exception e) {
            state.put("knowledgeIds", "[]");
        }
        return state;
    }
}
