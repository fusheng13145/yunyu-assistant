package com.leyon.backend.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.service.RustPBXService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class VoiceSignalingHandler extends TextWebSocketHandler {

    private final RustPBXService rustPBXService;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, String> sessionRustpbxMap = new ConcurrentHashMap<>();

    public VoiceSignalingHandler(RustPBXService rustPBXService, ObjectMapper objectMapper) {
        this.rustPBXService = rustPBXService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sendMessage(session, "connected", null);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.get("type").asText();

            switch (type) {
                case "offer" -> handleOffer(session, node);
                case "webrtc_connected" -> handleWebRtcConnected(session, node);
                case "hangup" -> handleHangup(session);
                default -> sendMessage(session, "error", "Unknown message type: " + type);
            }
        } catch (Exception e) {
            sendMessage(session, "error", "Failed to process message: " + e.getMessage());
        }
    }

    private void handleOffer(WebSocketSession session, JsonNode node) {
        String offerSDP = node.get("sdp").asText();
        String assistantId = node.has("assistantId") ? node.get("assistantId").asText() : null;

        try {
            String sessionId = rustPBXService.connectToRustPBX(offerSDP, assistantId,
                    answer -> sendMessage(session, "answer", Map.of("sdp", answer)),
                    asrText -> sendMessage(session, "asr", Map.of("text", asrText))
            );

            sessionRustpbxMap.put(session.getId(), sessionId);
        } catch (Exception e) {
            sendMessage(session, "error", "Failed to connect to voice server: " + e.getMessage());
        }
    }

    private void handleWebRtcConnected(WebSocketSession session, JsonNode node) {
        String greeting = node.has("greeting") ? node.get("greeting").asText() : "你好，我是云谕助手，有什么可以帮助你的吗？";
        String sessionId = session.getId();
        String rustpbxSessionId = sessionRustpbxMap.get(sessionId);
        if (rustpbxSessionId != null) {
            rustPBXService.sendTTS(rustpbxSessionId, greeting, "longxiaochun");
        }
        sendMessage(session, "webrtc_connected", null);
    }

    private void handleHangup(WebSocketSession session) {
        String sessionId = session.getId();
        String rustpbxSessionId = sessionRustpbxMap.remove(sessionId);
        if (rustpbxSessionId != null) {
            rustPBXService.disconnect(rustpbxSessionId);
        }
        sendMessage(session, "hangup", null);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = session.getId();
        String rustpbxSessionId = sessionRustpbxMap.remove(sessionId);
        if (rustpbxSessionId != null) {
            rustPBXService.disconnect(rustpbxSessionId);
        }
    }

    private void sendMessage(WebSocketSession session, String type, Object data) {
        if (!session.isOpen()) {
            return;
        }
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", type);
            if (data != null) {
                msg.put("data", data);
            }
            String json = objectMapper.writeValueAsString(msg);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception ignored) {
        }
    }
}
