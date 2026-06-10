package com.leyon.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RustPBXService {

    @Value("${app.rustpbx.endpoint}")
    private String endpoint;

    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;
    private final ConcurrentHashMap<String, WebSocket> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Consumer<String>> answerCallbacks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Consumer<String>> asrCallbacks = new ConcurrentHashMap<>();

    public RustPBXService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder().build();
    }

    public String connectToRustPBX(String offerSDP, String assistantId,
                                    Consumer<String> answerCallback,
                                    Consumer<String> asrCallback) {
        String sessionId = assistantId != null ? assistantId : UUID.randomUUID().toString();

        answerCallbacks.put(sessionId, answerCallback);
        asrCallbacks.put(sessionId, asrCallback);

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
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    JsonNode node = objectMapper.readTree(text);
                    String type = node.path("type").asText();

                    if ("answer".equals(type)) {
                        String answerSDP = node.path("sdp").asText();
                        Consumer<String> callback = answerCallbacks.get(sessionId);
                        if (callback != null) {
                            callback.accept(answerSDP);
                        }
                    } else if ("asr".equals(type)) {
                        String asrText = node.path("text").asText();
                        Consumer<String> callback = asrCallbacks.get(sessionId);
                        if (callback != null) {
                            callback.accept(asrText);
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                connections.remove(sessionId);
                answerCallbacks.remove(sessionId);
                asrCallbacks.remove(sessionId);
            }
        });

        connections.put(sessionId, webSocket);
        return sessionId;
    }

    public void disconnect(String sessionId) {
        WebSocket ws = connections.remove(sessionId);
        if (ws != null) {
            try {
                Map<String, Object> msg = Map.of("type", "bye", "session_id", sessionId);
                ws.send(objectMapper.writeValueAsString(msg));
            } catch (Exception ignored) {
            }
            ws.close(1000, "disconnect");
        }
        answerCallbacks.remove(sessionId);
        asrCallbacks.remove(sessionId);
    }

    public void sendTTS(String sessionId, String text, String speaker) {
        WebSocket ws = connections.get(sessionId);
        if (ws != null) {
            try {
                Map<String, Object> msg = Map.of(
                        "type", "tts",
                        "text", text,
                        "speaker", speaker,
                        "session_id", sessionId
                );
                ws.send(objectMapper.writeValueAsString(msg));
            } catch (Exception ignored) {
            }
        }
    }
}
