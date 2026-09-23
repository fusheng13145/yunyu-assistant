package com.leyon.backend.config;

import com.leyon.backend.handler.ChatWebSocketHandler;
import com.leyon.backend.handler.VoiceSignalingHandler;
import com.leyon.backend.interceptor.OpenApiWebSocketAuthInterceptor;
import com.leyon.backend.interceptor.WebSocketAuthInterceptor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 服务配置类
 * 注册聊天、语音信令 WebSocket 处理器，统一配置拦截器与跨域规则
 * v2.16 新增开放 OpenAPI 语音端点（/api/open/ws-voice/*，X-API-Key 鉴权，任意 Origin 放行）
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
    /** 开放 OpenAPI 语音信令 WebSocket 路由（X-API-Key 鉴权，第三方接入） */
    private static final String PATH_OPEN_VOICE = "/api/open/ws-voice/*";

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final VoiceSignalingHandler voiceSignalingHandler;
    private final WebSocketAuthInterceptor webSocketAuthInterceptor;
    private final OpenApiWebSocketAuthInterceptor openApiWebSocketAuthInterceptor;
    /** 允许握手的前端来源（v2.30 与 CorsConfig 共用 app.cors.allowed-origins；浏览器 WS 握手必带 Origin，白名单外一律 403） */
    private final String[] allowedOrigins;

    /**
     * 构造函数：注入聊天、语音信令处理器与认证拦截器
     */
    public WebSocketConfig(@NonNull ChatWebSocketHandler chatWebSocketHandler,
                           @NonNull VoiceSignalingHandler voiceSignalingHandler,
                           @NonNull WebSocketAuthInterceptor webSocketAuthInterceptor,
                           @NonNull OpenApiWebSocketAuthInterceptor openApiWebSocketAuthInterceptor,
                           @NonNull @Value("${app.cors.allowed-origins}") String[] allowedOrigins) {
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.voiceSignalingHandler = voiceSignalingHandler;
        this.webSocketAuthInterceptor = webSocketAuthInterceptor;
        this.openApiWebSocketAuthInterceptor = openApiWebSocketAuthInterceptor;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
        // 注册聊天 WebSocket
        registry.addHandler(chatWebSocketHandler, PATH_CHAT)
                .addInterceptors(webSocketAuthInterceptor)
                .setAllowedOrigins(allowedOrigins);

        // 注册语音信令 WebSocket
        registry.addHandler(voiceSignalingHandler, PATH_VOICE)
                .addInterceptors(webSocketAuthInterceptor)
                .setAllowedOrigins(allowedOrigins);

        // 注册开放 OpenAPI 语音信令 WebSocket（复用同一 VoiceSignalingHandler 单例，全链路信令/ASR/TTS/配额复用）
        // 跨域放行任意第三方 Origin（鉴权由 OpenApiWebSocketAuthInterceptor 的 API Key 承担）
        registry.addHandler(voiceSignalingHandler, PATH_OPEN_VOICE)
                .addInterceptors(openApiWebSocketAuthInterceptor)
                .setAllowedOriginPatterns("*");
    }
}