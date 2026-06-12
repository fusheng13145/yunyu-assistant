package com.leyon.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 音视频通话 WebSocket 服务
 * 对接 RustPBX 服务，实现 SDP 信令交互、语音转文字、语音合成推送
 *
 * @author leyon
 */
@Service
public class RustPBXService {

    /** WebSocket 服务地址 */
    @Value("${app.rustpbx.endpoint}")
    private String endpoint;

    /** 会话 WebSocket 连接缓存 */
    private final ConcurrentHashMap<String, WebSocket> connections = new ConcurrentHashMap<>();
    /** SDP Answer 回调缓存 */
    private final ConcurrentHashMap<String, Consumer<String>> answerCallbacks = new ConcurrentHashMap<>();
    /** ASR 语音转文字回调缓存 */
    private final ConcurrentHashMap<String, Consumer<String>> asrCallbacks = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    public RustPBXService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // 全局单例 OkHttpClient，避免重复创建
        this.httpClient = new OkHttpClient.Builder().build();
    }

    /**
     * 建立 WebSocket 连接并发起通话邀请
     *
     * @param offerSDP    本地 SDP 协商信息
     * @param assistantId 助手ID（用作会话标识，可为空）
     * @param answerCallback 远端 Answer SDP 回调
     * @param asrCallback    语音转文字结果回调
     * @return 会话ID
     */
    public String connectToRustPBX(String offerSDP, String assistantId,
                                    Consumer<String> answerCallback,
                                    Consumer<String> asrCallback) {
        // 生成会话ID
        String sessionId = StringUtils.hasText(assistantId) ? assistantId : UUID.randomUUID().toString();

        // 注册回调
        if (answerCallback != null) {
            answerCallbacks.put(sessionId, answerCallback);
        }
        if (asrCallback != null) {
            asrCallbacks.put(sessionId, asrCallback);
        }

        Request request = new Request.Builder()
                .url(endpoint)
                .build();

        WebSocket webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                try {
                    Map<String, Object> inviteMsg = Map.of(
                            "type", "invite",
                            "sdp", offerSDP,
                            "session_id", sessionId
                    );
                    ws.send(objectMapper.writeValueAsString(inviteMsg));
                } catch (Exception e) {
                    // 消息发送异常，静默处理
                }
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (!StringUtils.hasText(text)) {
                    return;
                }
                try {
                    JsonNode node = objectMapper.readTree(text);
                    String type = node.path("type").asText("");

                    if ("answer".equals(type)) {
                        String answerSdp = node.path("sdp").asText("");
                        Consumer<String> callback = answerCallbacks.get(sessionId);
                        if (callback != null && StringUtils.hasText(answerSdp)) {
                            callback.accept(answerSdp);
                        }
                    } else if ("asr".equals(type)) {
                        String asrText = node.path("text").asText("");
                        Consumer<String> callback = asrCallbacks.get(sessionId);
                        if (callback != null && StringUtils.hasText(asrText)) {
                            callback.accept(asrText);
                        }
                    }
                } catch (Exception e) {
                    // 消息解析异常，静默处理
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                // 连接异常，清理会话资源
                clearSessionResource(sessionId);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                // 连接正常关闭，清理会话资源
                clearSessionResource(sessionId);
            }
        });

        connections.put(sessionId, webSocket);
        return sessionId;
    }

    /**
     * 断开指定会话连接，释放资源
     *
     * @param sessionId 会话ID
     */
    public void disconnect(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        WebSocket ws = connections.remove(sessionId);
        if (ws == null) {
            return;
        }
        try {
            Map<String, Object> byeMsg = Map.of("type", "bye", "session_id", sessionId);
            ws.send(objectMapper.writeValueAsString(byeMsg));
        } catch (Exception e) {
            // 下线消息发送失败，继续执行关闭逻辑
        }
        // 正常关闭 WebSocket
        ws.close(1000, "client disconnect");
        clearSessionResource(sessionId);
    }

    /**
     * 发送 TTS 文本转语音消息
     *
     * @param sessionId 会话ID
     * @param text      待合成文本
     * @param speaker   音色/发言人标识
     */
    public void sendTTS(String sessionId, String text, String speaker) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(text)) {
            return;
        }
        WebSocket ws = connections.get(sessionId);
        if (ws == null) {
            return;
        }
        try {
            Map<String, Object> ttsMsg = Map.of(
                    "type", "tts",
                    "text", text,
                    "speaker", speaker,
                    "session_id", sessionId
            );
            ws.send(objectMapper.writeValueAsString(ttsMsg));
        } catch (Exception e) {
            // 消息发送异常，静默处理
        }
    }

    /**
     * 统一清理会话缓存资源
     *
     * @param sessionId 会话ID
     */
    private void clearSessionResource(String sessionId) {
        connections.remove(sessionId);
        answerCallbacks.remove(sessionId);
        asrCallbacks.remove(sessionId);
    }
}