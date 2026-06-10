package com.leyon.backend.config;

import com.leyon.backend.websocket.ChatWebSocketHandler;
import com.leyon.backend.websocket.VoiceSignalingHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final VoiceSignalingHandler voiceSignalingHandler;

    public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler,
                          VoiceSignalingHandler voiceSignalingHandler) {
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.voiceSignalingHandler = voiceSignalingHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/*")
                .setAllowedOrigins("*");
        registry.addHandler(voiceSignalingHandler, "/ws-voice/*")
                .setAllowedOrigins("*");
    }
}
