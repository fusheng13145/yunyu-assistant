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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 音视频通话 WebSocket 服务
 * 对接 RustPBX 服务，实现 SDP 信令交互、语音转文字、语音合成推送、打断与沉默追问
 *
 * @author leyon
 */
@Service
public class RustPBXService {

    /** WebSocket 服务地址 */
    @Value("${app.rustpbx.endpoint}")
    private String endpoint;

    /** 默认 TTS 音色（助手未指定时兜底） */
    @Value("${app.tts.speaker:longxiaochun}")
    private String defaultSpeaker;

    /** TTS 模型 */
    @Value("${app.tts.model:cosvoice-v1}")
    private String ttsModel;

    /** ASR 供应商 */
    @Value("${app.asr.provider:tencent}")
    private String asrProvider;

    /** ASR 模型 */
    @Value("${app.asr.model:16k_zh}")
    private String asrModel;

    /** 沉默追问超时（秒） */
    @Value("${app.rustpbx.silence-timeout:30}")
    private int silenceTimeout;

    /** 是否启用 VAD 打断 TTS */
    @Value("${app.rustpbx.break-on-vad:true}")
    private boolean breakOnVad;

    /** 会话 WebSocket 连接缓存 */
    private final ConcurrentHashMap<String, WebSocket> connections = new ConcurrentHashMap<>();
    /** SDP Answer 回调缓存 */
    private final ConcurrentHashMap<String, Consumer<String>> answerCallbacks = new ConcurrentHashMap<>();
    /** ASR 语音转文字回调缓存 */
    private final ConcurrentHashMap<String, Consumer<String>> asrCallbacks = new ConcurrentHashMap<>();
    /** 沉默追问回调缓存（用户超时未说话时触发） */
    private final ConcurrentHashMap<String, Consumer<String>> silenceCallbacks = new ConcurrentHashMap<>();
    /** 沉默计时任务缓存 */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> silenceTasks = new ConcurrentHashMap<>();

    /** 沉默计时线程池（每个会话一个单次定时任务，共享调度器） */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    public RustPBXService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // 创建带超时配置的 OkHttpClient，语音通话需要较长读超时
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .pingInterval(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 建立 WebSocket 连接并发起通话邀请
     *
     * @param offerSDP    本地 SDP 协商信息
     * @param assistantId 助手ID（用作会话标识，可为空）
     * @param speaker     助手音色代码（为空时使用默认音色）
     * @param answerCallback 远端 Answer SDP 回调
     * @param asrCallback    语音转文字结果回调
     * @param silenceCallback 沉默追问回调（可为空）
     * @return 会话ID
     */
    public String connectToRustPBX(String offerSDP, String assistantId, String speaker,
                                    Consumer<String> answerCallback,
                                    Consumer<String> asrCallback,
                                    Consumer<String> silenceCallback) {
        // 生成会话ID：必须全局唯一，避免同一助手并发呼叫时回调缓存相互覆盖
        String sessionId = UUID.randomUUID().toString();

        // 音色兜底
        String voice = StringUtils.hasText(speaker) ? speaker : defaultSpeaker;

        // 注册回调
        if (answerCallback != null) {
            answerCallbacks.put(sessionId, answerCallback);
        }
        if (asrCallback != null) {
            asrCallbacks.put(sessionId, asrCallback);
        }
        if (silenceCallback != null) {
            silenceCallbacks.put(sessionId, silenceCallback);
        }

        Request request = new Request.Builder()
                .url(endpoint)
                .build();

        WebSocket webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                try {
                    // invite 消息携带 callOption（降噪/VAD/ASR/TTS/音色/打断/沉默超时），对齐 Go 版 rustpbxgo 协议
                    Map<String, Object> callOption = new HashMap<>();
                    callOption.put("denoise", true);
                    callOption.put("vad", Map.of("type", "silero"));
                    callOption.put("asr", Map.of("provider", asrProvider, "model_type", asrModel));
                    callOption.put("tts", Map.of("provider", "tencent", "speaker", voice, "model", ttsModel));
                    callOption.put("break_on_vad", breakOnVad);
                    callOption.put("silence_timeout", silenceTimeout);

                    Map<String, Object> inviteMsg = new HashMap<>();
                    inviteMsg.put("type", "invite");
                    inviteMsg.put("sdp", offerSDP);
                    inviteMsg.put("session_id", sessionId);
                    inviteMsg.put("call_option", callOption);
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
                    } else if ("speaking".equals(type)) {
                        // 用户开始说话：取消沉默计时
                        cancelSilenceTask(sessionId);
                    } else if ("track_end".equals(type) || "trackEnd".equals(type)) {
                        // 音轨播放结束：重置沉默计时
                        scheduleSilenceTask(sessionId);
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
     * 启动沉默计时任务：超时后触发沉默追问回调
     */
    private void scheduleSilenceTask(String sessionId) {
        cancelSilenceTask(sessionId);
        Consumer<String> callback = silenceCallbacks.get(sessionId);
        if (callback == null) {
            return;
        }
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            Consumer<String> cb = silenceCallbacks.get(sessionId);
            if (cb != null) {
                cb.accept("您还在吗？请说点什么吧!");
            }
        }, silenceTimeout, TimeUnit.SECONDS);
        silenceTasks.put(sessionId, future);
    }

    /**
     * 取消沉默计时任务
     */
    private void cancelSilenceTask(String sessionId) {
        ScheduledFuture<?> future = silenceTasks.remove(sessionId);
        if (future != null) {
            future.cancel(false);
        }
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
            String voice = StringUtils.hasText(speaker) ? speaker : defaultSpeaker;
            Map<String, Object> ttsMsg = Map.of(
                    "type", "tts",
                    "text", text,
                    "speaker", voice,
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
        silenceCallbacks.remove(sessionId);
        cancelSilenceTask(sessionId);
    }
}