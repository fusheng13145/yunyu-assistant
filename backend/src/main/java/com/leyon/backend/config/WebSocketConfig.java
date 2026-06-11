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
    private final WebSocketAuthInterceptor webSocketAuthInterceptor;

    public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler,
                          VoiceSignalingHandler voiceSignalingHandler,
                          WebSocketAuthInterceptor webSocketAuthInterceptor) {
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.voiceSignalingHandler = voiceSignalingHandler;
        this.webSocketAuthInterceptor = webSocketAuthInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/*")
                .addInterceptors(webSocketAuthInterceptor)
                .setAllowedOrigins("http://localhost:5173", "http://localhost:3000", "http://127.0.0.1:5173");
        registry.addHandler(voiceSignalingHandler, "/ws-voice/*")
                .addInterceptors(webSocketAuthInterceptor)
                .setAllowedOrigins("http://localhost:5173", "http://localhost:3000", "http://127.0.0.1:5173");
    }
}
