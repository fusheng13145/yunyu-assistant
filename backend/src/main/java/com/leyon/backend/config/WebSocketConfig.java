package com.leyon.backend.config;

import com.leyon.backend.handler.ChatWebSocketHandler;
import com.leyon.backend.handler.VoiceSignalingHandler;
import com.leyon.backend.interceptor.WebSocketAuthInterceptor;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 服务配置类
 * 注册聊天、语音信令 WebSocket 处理器，统一配置拦截器与跨域规则
 *
 * @author leyon
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /** 聊天 WebSocket 路由 */
    private static final String PATH_CHAT = "/ws/*";
    /** 语音信令 WebSocket 路由 */
    private static final String PATH_VOICE = "/ws-voice/*";
    /** 允许跨域的前端域名列表 */
    private static final String[] ALLOWED_ORIGINS = {
            "http://localhost:5173",
            "http://localhost:3000",
            "http://127.0.0.1:5173",
            "http://127.0.0.1:3000"
    };

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final VoiceSignalingHandler voiceSignalingHandler;
    private final WebSocketAuthInterceptor webSocketAuthInterceptor;

    /**
     * 构造函数：注入聊天、语音信令处理器与认证拦截器
     */
    public WebSocketConfig(@NonNull ChatWebSocketHandler chatWebSocketHandler,
                           @NonNull VoiceSignalingHandler voiceSignalingHandler,
                           @NonNull WebSocketAuthInterceptor webSocketAuthInterceptor) {
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.voiceSignalingHandler = voiceSignalingHandler;
        this.webSocketAuthInterceptor = webSocketAuthInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
        // 注册聊天 WebSocket
        registry.addHandler(chatWebSocketHandler, PATH_CHAT)
                .addInterceptors(webSocketAuthInterceptor)
                .setAllowedOrigins(ALLOWED_ORIGINS);

        // 注册语音信令 WebSocket
        registry.addHandler(voiceSignalingHandler, PATH_VOICE)
                .addInterceptors(webSocketAuthInterceptor)
                .setAllowedOrigins(ALLOWED_ORIGINS);
    }
}